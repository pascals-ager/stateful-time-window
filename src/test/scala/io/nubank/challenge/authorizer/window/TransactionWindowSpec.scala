package io.nubank.challenge.authorizer.window

import cats.effect.{ContextShift, IO, Resource, Timer}
import cats.effect.concurrent.Ref
import org.scalatest.funspec.AnyFunSpec
import fs2.Stream
import io.nubank.challenge.authorizer.external.ExternalDomain._
import cats.implicits._
import com.google.common.cache.{Cache, CacheBuilder}
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scalacache.{Entry, Mode}
import scalacache.guava.GuavaCache

import java.time.Clock
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class TransactionWindowSpec extends AnyFunSpec {

  implicit val timer: Timer[IO]                      = IO.timer(ExecutionContext.global)
  implicit val cs: ContextShift[IO]                  = IO.contextShift(ExecutionContext.global)
  implicit val mode: Mode[IO]                        = scalacache.CatsEffect.modes.async
  implicit val clock: Clock                          = Clock.systemUTC()
  val cacheExpirationInterval: FiniteDuration        = 30.seconds
  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  val build: IO[Ref[IO, GuavaCache[ListBuffer[(Long, Long)]]]] = Ref[IO].of(
    new GuavaCache(
      CacheBuilder
        .newBuilder()
        .expireAfterWrite(cacheExpirationInterval._1, cacheExpirationInterval._2)
        .maximumSize(100L)
        .build[String, Entry[ListBuffer[(Long, Long)]]]
    )
  )

  val res: Resource[IO, Ref[IO, GuavaCache[ListBuffer[(Long, Long)]]]] = Resource
    .make(build) { window =>
      window.modify(win => {
        win.underlying.invalidateAll()
        (win, ())
      })
    }

  it("Write and read two entries") {
    val basicOperationsStream: Stream[IO, IO[Seq[Option[Seq[(Long, Long)]]]]] = Stream
      .resource(res)
      .map { cache =>
        val win: TransactionWindow = new TransactionWindow(cache)
        for {
          tsOne          <- IO.pure(System.currentTimeMillis())
          _              <- logger.info(s"Using ${tsOne} for first transaction")
          transactionOne <- IO.pure(Transaction("Nike", 240, 1581256213, tsOne))
          _              <- win.put(transactionOne)
          _              <- logger.info(s"First Transaction Success")
          tsTwo          <- IO.pure(System.currentTimeMillis())
          _              <- logger.info(s"Using ${tsTwo} for second transaction ")
          transactionTwo <- IO.pure(Transaction("Addidas", 220, 1581256214, tsTwo))
          _              <- win.put(transactionTwo)
          _              <- logger.info(s"Second Transaction Success")
          entryOne       <- win.get("Nike", 240)
          entryTwo       <- win.get("Addidas", 220)
        } yield Vector(entryOne, entryTwo)
      }
    val basicOperationsTest = for {
      seq   <- basicOperationsStream
      items <- Stream.eval(seq)
    } yield assert(items.size == 2 && items(0).get.head._1 == 1581256213 && items(1).get.head._1 == 1581256214)
    basicOperationsTest.compile.drain.unsafeRunSync()
  }

  it("Write and read two entries with same key") {
    val timestampEvictionInterval: FiniteDuration = 10.seconds
    val multiKeyOperationsStream: Stream[IO, IO[Option[Seq[(Long, Long)]]]] = Stream.resource(res).map { cache =>
      val win: TransactionWindow = new TransactionWindow(cache)
      for {
        tsOne          <- IO.pure(System.currentTimeMillis())
        _              <- logger.info(s"Using ${tsOne} for first transaction")
        transactionOne <- IO.pure(Transaction("Nike", 240, 1581256223, tsOne))
        _              <- win.put(transactionOne)
        _              <- logger.info(s"First Transaction Success")
        tsTwo          <- IO.pure(System.currentTimeMillis())
        _              <- logger.info(s"Using ${tsTwo} for second transaction ")
        transactionTwo <- IO.pure(Transaction("Nike", 240, 1581256224, tsTwo))
        _              <- win.put(transactionTwo)
        _              <- logger.info(s"Second Transaction Success")
        entryOne       <- win.get("Nike", 240)
      } yield entryOne
    }
    val multiKeyOperationsTest = for {
      value <- multiKeyOperationsStream
      items <- Stream.eval(value)
    } yield assert(items.get.size == 2 && (items.get)(0)._1 == 1581256223 && (items.get)(1)._1 == 1581256224)
    multiKeyOperationsTest.compile.drain.unsafeRunSync()
  }

  it("Older entry of the same key should expire") {
    val timestampEvictionInterval: FiniteDuration = 10.seconds
    val timestampExpirationStream: Stream[IO, Option[Seq[(Long, Long)]]] = Stream.resource(res).flatMap { cache =>
      val win: TransactionWindow = new TransactionWindow(cache)

      val step: Stream[IO, Option[Seq[(Long, Long)]]] = Stream.eval(for {
        tsOne          <- IO.pure(System.currentTimeMillis())
        _              <- logger.info(s"Using ${tsOne} for first transaction")
        transactionOne <- IO.pure(Transaction("Nike", 240, 1581256283, tsOne))
        _              <- win.put(transactionOne)
        _              <- logger.info(s"First Transaction Success")
        _              <- IO.delay(Thread.sleep(20000))
        tsTwo          <- IO.pure(System.currentTimeMillis())
        _              <- logger.info(s"Using ${tsTwo} for second transaction ")
        transactionTwo <- IO.pure(Transaction("Nike", 240, 1581256284, tsTwo))
        _              <- win.put(transactionTwo)
        _              <- logger.info(s"Second Transaction Success")
        get            <- win.get("Nike", 240)
      } yield get)

      val evict = Stream
        .eval(win.evictExpiredTimestamps(timestampEvictionInterval))
        .metered(3.seconds)
        .repeatN(20)

      step
        .concurrently(evict)
    }
    val timestampExpirationTest = for {
      value <- timestampExpirationStream
    } yield assert(value.get.size == 1 && value.get.head._1 == 1581256284)

    timestampExpirationTest.compile.drain.unsafeRunSync()
  }

  it("Size of window with distinct key transactions") {
    val windowSizeStream: Stream[IO, IO[Int]] = Stream.resource(res).map { cache =>
      val win: TransactionWindow = new TransactionWindow(cache)
      for {
        tsOne            <- IO.pure(System.currentTimeMillis())
        _                <- logger.info(s"Using ${tsOne} for first transaction")
        transactionOne   <- IO.pure(Transaction("Nike", 240, 1581256263, tsOne))
        _                <- win.put(transactionOne)
        _                <- logger.info(s"First Transaction Success")
        tsTwo            <- IO.pure(System.currentTimeMillis())
        _                <- logger.info(s"Using ${tsTwo} for second transaction ")
        transactionTwo   <- IO.pure(Transaction("Addidas", 240, 1581256264, tsTwo))
        _                <- win.put(transactionTwo)
        tsThree          <- IO.pure(System.currentTimeMillis())
        _                <- logger.info(s"Using ${tsThree} for third transaction ")
        transactionThree <- IO.pure(Transaction("Puma", 240, 1581256265, tsThree))
        _                <- win.put(transactionThree)
        _                <- logger.info(s"Third Transaction Success")
        size             <- win.getSize
      } yield size
    }
    val windowSizeTest = for {
      value <- windowSizeStream
      items <- Stream.eval(value)
    } yield assert(items == 3)
    windowSizeTest.compile.drain.unsafeRunSync()
  }

  it("Size of window with cache expiration and distinct key transactions") {
    val windowSizeStream: Stream[IO, IO[Int]] = Stream.resource(res).map { cache =>
      val win: TransactionWindow = new TransactionWindow(cache)
      for {
        tsOne            <- IO.pure(System.currentTimeMillis())
        _                <- logger.info(s"Using ${tsOne} for first transaction")
        transactionOne   <- IO.pure(Transaction("Nike", 240, 1581256263, tsOne))
        _                <- win.put(transactionOne)
        _                <- logger.info(s"First Transaction Success")
        tsTwo            <- IO.pure(System.currentTimeMillis())
        _                <- logger.info(s"Using ${tsTwo} for second transaction ")
        transactionTwo   <- IO.pure(Transaction("Addidas", 240, 1581256264, tsTwo))
        _                <- win.put(transactionTwo)
        _                <- IO.delay(Thread.sleep(60000))
        tsThree          <- IO.pure(System.currentTimeMillis())
        _                <- logger.info(s"Using ${tsThree} for third transaction ")
        transactionThree <- IO.pure(Transaction("Puma", 240, 1581256265, tsThree))
        _                <- win.put(transactionThree)
        _                <- logger.info(s"Third Transaction Success")
        size             <- win.getSize
      } yield size
    }
    val windowSizeTest = for {
      value <- windowSizeStream
      items <- Stream.eval(value)
    } yield assert(items == 1)
    windowSizeTest.compile.drain.unsafeRunSync()
  }

  it("Size of window with entry expiration and multi-key transactions") {
    val timestampEvictionInterval: FiniteDuration = 10.seconds
    val windowSizeStream: Stream[IO, Int] = Stream.resource(res).flatMap { cache =>
      val win: TransactionWindow = new TransactionWindow(cache)
      val step: Stream[IO, Int] = Stream.eval(for {
        tsOne            <- IO.pure(System.currentTimeMillis())
        _                <- logger.info(s"Using ${tsOne} for first transaction")
        transactionOne   <- IO.pure(Transaction("Nike", 240, 1581256263, tsOne))
        _                <- win.put(transactionOne)
        _                <- logger.info(s"First Transaction Success")
        tsTwo            <- IO.pure(System.currentTimeMillis())
        _                <- logger.info(s"Using ${tsTwo} for second transaction ")
        transactionTwo   <- IO.pure(Transaction("Nike", 240, 1581256264, tsTwo))
        _                <- win.put(transactionTwo)
        _                <- IO.delay(Thread.sleep(30000))
        tsThree          <- IO.pure(System.currentTimeMillis())
        _                <- logger.info(s"Using ${tsThree} for third transaction ")
        transactionThree <- IO.pure(Transaction("Nike", 240, 1581256265, tsThree))
        _                <- win.put(transactionThree)
        _                <- logger.info(s"Third Transaction Success")
        size             <- win.getSize
      } yield size)

      val evict = Stream
        .eval(win.evictExpiredTimestamps(timestampEvictionInterval))
        .metered(3.seconds)
        .repeatN(20)

      step
        .concurrently(evict)

    }
    val windowSizeTest = for {
      value <- windowSizeStream
    } yield assert(value == 1)
    windowSizeTest.compile.drain.unsafeRunSync()
  }

}
