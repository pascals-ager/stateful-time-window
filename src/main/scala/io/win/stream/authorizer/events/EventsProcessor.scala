package io.win.stream.authorizer.events

import cats.effect.concurrent.{Deferred, Semaphore}
import cats.effect.{Blocker, ContextShift, IO, Timer}
import fs2.{Pipe, Stream}
import fs2.concurrent.Topic
import io.circe.DecodingFailure
import io.circe.parser.parse
import io.circe.syntax.EncoderOps
import io.win.stream.authorizer.exception.DomainException.{
  DecodingFailureException,
  ParsingFailureException,
  UnrecognizedEventException
}
import io.win.stream.authorizer.external.ExternalDomain.{
  AccountEvent,
  AccountState,
  ExternalEvent,
  Start,
  TransactionEvent
}
import io.win.stream.authorizer.stores.AccountStoreService
import io.win.stream.authorizer.window.TransactionWindow
import org.typelevel.log4cats.Logger

import scala.util.Random

class EventsProcessor(
    store: AccountStoreService,
    window: TransactionWindow,
    topic: Topic[IO, Either[DecodingFailure, ExternalEvent]],
    semaphore: Semaphore[IO]
)(
    implicit timer: Timer[IO],
    threadpool: ContextShift[IO],
    logger: Logger[IO]
) {

  //implicit val semaphore: Stream[IO, Semaphore[IO]] = Stream.eval(Semaphore[IO](1))
  val accountsHandler     = new AccountsProcessor(store)
  val transactionsHandler = new TransactionsProcessor(store, window)
  def consumeEvents: Stream[IO, Unit] =
    Stream
      .resource(Blocker[IO])
      .flatMap { blocker =>
        fs2.io
          .stdinUtf8[IO](4096, blocker)
          .repeat
          .through(fs2.text.lines)
          .filter(_.nonEmpty)
          .through(eventsClassificationPipe)
          .through(eventsPublishPipe)
      }

  def eventsClassificationPipe: Pipe[IO, String, Either[DecodingFailure, ExternalEvent]] =
    _.flatMap { in =>
      parse(in) match {
        case Right(value) =>
          if (value.findAllByKey("account").nonEmpty) {
            for {
              _   <- Stream.eval(logger.info("Received AccountEvent: Encoding."))
              enc <- Stream.eval(IO.delay(value.as[AccountEvent]))
            } yield enc
          } else if (value.findAllByKey("transaction").nonEmpty) {
            for {
              _   <- Stream.eval(logger.info("Received TransactionEvent: Encoding with processingTime timestamp"))
              enc <- Stream.eval(IO.delay(value.as[TransactionEvent]))
            } yield enc
          } else {
            Stream.raiseError[IO](
              UnrecognizedEventException("Undefined event received. Expecting account or transaction event types only.")
            )
          }
        case Left(ex) => Stream.raiseError[IO](ParsingFailureException(ex.message))
      }
    }

  def eventsPublishPipe: Pipe[IO, Either[DecodingFailure, ExternalEvent], Unit] =
    _.flatMap { in =>
      for {
        _   <- Stream.eval(logger.info("Received AccountEvent: Publishing to topic."))
        pub <- Stream.eval(topic.publish1(in))
      } yield pub
    }

  def authorizeEvents(
      semaphore: Semaphore[IO]
  ): Pipe[IO, Either[DecodingFailure, ExternalEvent], Option[AccountState]] =
    _.flatMap {
      case Left(ex) => Stream.raiseError[IO](DecodingFailureException(ex.message))
      case Right(value) =>
        value match {
          case AccountEvent(account) =>
            for {
              _ <- Stream.eval(semaphore.acquire)
              _ <- Stream.eval(logger.debug(s"Acquired semaphore for AccountEvent ${account}"))
              acctState <- Stream
                .eval(accountsHandler.validateAndPutAccount(account))
              _ <- Stream.eval(logger.debug(s"Releasing semaphore for AccountEvent ${account}"))
              _ <- Stream.eval(semaphore.release)
            } yield Some(acctState)

          case TransactionEvent(transaction) =>
            for {
              _ <- Stream.eval(logger.debug(s"Acquired semaphore for TransactionEvent ${transaction}"))
              _ <- Stream.eval(semaphore.acquire)
              acctState <- Stream
                .eval(transactionsHandler.validateAndPutTransaction(transaction))
              _ <- Stream.eval(logger.debug(s"Releasing semaphore for TransactionEvent ${transaction}"))
              _ <- Stream.eval(semaphore.release)
            } yield Some(acctState)

          case Start => Stream.emit(None)
        }
    }

  def publishState: Pipe[IO, Option[AccountState], Unit] = _.flatMap { in =>
    val publish: Stream[IO, Unit] = for {
      _ <- Stream.eval(logger.debug("Publishing AccountState to stdout."))
      pub <- in match {
        case Some(acctState) => Stream.eval(IO.delay(println(acctState.asJson)))
        case None            => Stream.emit(())
      }
    } yield pub
    publish
  }

  def eventsSubscriber: Stream[IO, Either[DecodingFailure, ExternalEvent]] = {
    topic.subscribe(10)
  }

  def eventsHandler: Stream[IO, Unit] = {
    eventsSubscriber
      .through(authorizeEvents(semaphore))
      .through(publishState)
  }
}
