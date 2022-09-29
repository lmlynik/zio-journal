package example

import com.devsisters.shardcake.Messenger.Replier
import com.devsisters.shardcake.{ EntityType, Sharding }
import zio._

import scala.util.{ Failure, Success, Try }
import pl.mlynik.EventSourcedEntity
import pl.mlynik.EventSourcedEntity.EntityEnvironment
import pl.mlynik.journal.EntityRef
import pl.mlynik.Effect
import pl.mlynik.Effect.EffectIO
import pl.mlynik.journal.Journal
import pl.mlynik.EntityManager
import pl.mlynik.journal.SnapshotStorage
import pl.mlynik.journal.Storage

object GuildBehavior {
  sealed trait GuildMessage

  object GuildMessage {
    case class Join(userId: String, replier: Replier[Try[Set[String]]]) extends GuildMessage
    case class Leave(userId: String)                                    extends GuildMessage
  }

  sealed trait GuildMesssageError

  sealed trait GuildEvent
  object GuildEvent {
    case class Joined(userId: String) extends GuildEvent
    case class Left(userId: String)   extends GuildEvent
  }

  final case class GuildState(state: Set[String]) {
    def join(userId: String)  = copy(state = state + userId)
    def leave(userId: String) = copy(state = state - userId)
  }

  object Guild extends EntityType[GuildMessage]("guild")

  def commandHandler(state: GuildState, message: GuildMessage)(using
    trace: Trace
  ): EffectIO[Sharding, GuildMesssageError, GuildEvent] = {
    import Effect.*
    message match {
      case GuildMessage.Join(userId, replier) =>
        if (state.state.size >= 5)
          replier.reply(Failure(new Exception("Guild is already full!"))) *> none
        else {
          persistZIO(GuildEvent.Joined(userId)) >>> (replier.reply(Success(state.state + userId)) *> none)
        }
      case GuildMessage.Leave(userId)         =>
        persistZIO(GuildEvent.Left(userId))
    }
  }

  def eventHandler(state: GuildState, evt: GuildEvent): ZIO[Any, Nothing, GuildState] =
    evt match
      case GuildEvent.Joined(userId) => ZIO.succeed(state.join(userId))
      case GuildEvent.Left(userId)   => ZIO.succeed(state.leave(userId))

  type GuildBehaviorEnvironment =
    EntityEnvironment[Any, Sharding, GuildMessage, GuildMesssageError, GuildEvent, GuildState]

  def entity(entityId: String) =
    EventSourcedEntity[Any, Sharding, GuildMessage, GuildMesssageError, GuildEvent, GuildState](
      persistenceId = entityId,
      emptyState = GuildState(Set.empty),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    ).mapError(loadError => new Throwable(loadError.toString))

  def behavior(entityId: String, messages: Dequeue[GuildMessage]) =
    entity(entityId).flatMap { entity =>
      messages.take.flatMap { message =>
        entity.send(message).mapError(error => new Throwable(error.toString))
      }.forever
    }
}
