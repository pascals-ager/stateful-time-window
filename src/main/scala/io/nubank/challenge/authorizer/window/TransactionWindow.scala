package io.nubank.challenge.authorizer.window

import cats.effect.IO
import cats.effect.concurrent.Ref
import io.nubank.challenge.authorizer.external.ExternalDomain.Transaction
import org.typelevel.log4cats.Logger
import scalacache.{Entry, Mode}
import scalacache.guava.GuavaCache

import java.time.Clock
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.{ConcurrentMapHasAsScala, IterableHasAsScala}

class TransactionWindow(window: Ref[IO, GuavaCache[ListBuffer[(Long, Long)]]])(
    implicit mode: Mode[IO],
    clock: Clock,
    logger: Logger[IO]
) {

  def put(transaction: Transaction): IO[Unit] =
    for {
      mod <- window.update(win =>
        Option(win.underlying.getIfPresent(transaction.merchant + transaction.amount.toString)) match {
          case Some(entry) =>
            entry.value += Tuple2(transaction.transactionTime, transaction.processingTime)
            win
          case None =>
            win.underlying.put(
              transaction.merchant + transaction.amount.toString,
              Entry(ListBuffer((transaction.transactionTime, transaction.processingTime)), None)
            )
            win
        }
      )
    } yield mod

  def getSize: IO[Int] =
    for {
      win <- window.get
      size <- IO.delay {
        var size = 0
        for (entries <- win.underlying.asMap().values().asScala) {
          size += entries.value.size
        }
        size
      }
    } yield size

  def get(merchant: String, amount: Int): IO[Option[Seq[(Long, Long)]]] =
    for {
      win <- window.get
      item <- IO.delay {
        Option(win.underlying.getIfPresent(merchant + amount.toString)) match {
          case Some(entry) => Some(entry.value.toSeq)
          case None        => None
        }
      }
    } yield item

  def evictExpiredTimestamps(timestampEvictionInterval: FiniteDuration): IO[Unit] =
    for {
      curr <- IO.delay(System.currentTimeMillis())
      _ <- logger.info(
        s"Evicting expired timestamps at ${curr} with evictionInterval ${timestampEvictionInterval.toMillis} ms"
      )
      mod <- window.update(win => {
        for ((key, entry) <- win.underlying.asMap().asScala) {
          entry.value.filterInPlace(item => (curr - item._2) <= timestampEvictionInterval.toMillis)
        }
        win
      })
    } yield mod

}
