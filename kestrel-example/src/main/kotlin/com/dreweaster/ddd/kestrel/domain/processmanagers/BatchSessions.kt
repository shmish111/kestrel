package com.dreweaster.ddd.kestrel.domain.processmanagers

import com.dreweaster.ddd.kestrel.application.*
import com.dreweaster.ddd.kestrel.application.consumers.BoundedContexts
import com.dreweaster.ddd.kestrel.application.eventstream.EventStreamSubscriptionEdenPolicy
import com.dreweaster.ddd.kestrel.domain.*
import com.dreweaster.ddd.kestrel.domain.aggregates.user.RegisterUser
import com.dreweaster.ddd.kestrel.domain.aggregates.user.User
import com.dreweaster.ddd.kestrel.domain.aggregates.user.UserIsLocked
import com.dreweaster.ddd.kestrel.domain.aggregates.user.UserRegistered
import com.dreweaster.ddd.kestrel.infrastructure.InMemoryBackend
import io.vavr.control.Try
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant

// Really important to note that any async actions, dispatched commands, emitted events will be triggered with at-least-once semantics
// If any of these fail, then they will all be re-executed. So, it's important to apply the idempotent receiver pattern
// Events emitted to self will be idempotently handled within configured period*, and will be de-duped based on event id.
//
// *Can configure how many event ids to retain in history. When not using snapshotting, this is irrelevant as all event ids
// will be retained. It comes in to play only with snapshots which can be configured to store a variable number of historic event ids
//
// It's not acceptable to run async DomainService functions outside of an async action block. Behaviour is undefined if attempted.
//
// fun DomainModel.registerProcessManager(materialiser: StatefulProcessManagerMaterialiser)

// Define as DSL (at application layer, references PM blueprint at domain layer)
// StatefulProcessManagerMaterialiser

// materialise<BatchSessions>(name = "batch-sessions") { -- derive event sourcing configuration via app config based on name
    //
    // consumer {
    //
    //    subscribe(context = BoundedContexts.UserContext) {
    //
    //        event<ParkingSessionCompleted> {
    //           correlationIdGenerator { event, metadata -> "${evt.parkableVehicleId}-${evt.carParkId}" }
    //           mapper { evt -> ParkingSessionQueued(evt.name) }
    //        }
    //
    //        event<ChargeableParkingSessionsBatched> {
    //           correlationIdGenerator { event, metadata -> metadata.correlationId }
    //           mapper { evt -> BatchCreated }
    //        }
    //    }
    // }
    //
    // contextBuilder { event, metadata, state ->
    //    val carParkConfiguration = carParkService.getCarPark(state.carParkId)
    //    return object : BatchSessionsContext {
    //        override val bufferPeriod = carParkConfiguration.bufferPeriod
    //    }
    // }
    //
    // retryStrategy { failureCount, error - > }
    //
    // }
// }

// Need to map external events into the Policy's own protocol
// EventIds of internal events are taken from the external event
// This approach means the event format migrations can be easily reasoned about in an isolated way

// domainModel.processManagerOf(BatchSessions).storeEvent(event) // This will apply any mappers as necessary
// domainModel.processManagerOf(BatchSessions).instancesAwaitingProcessing(pageable): Page<ProcessManagerId> // orders by event with oldest outstanding unprocessed event
// domainModel.processManagerOf(BatchSessions).instanceOf(id).process() // Manually trigger process manager to execute - only useful if has events awaiting processing, does nowt otherwise
// domainModel.processManagerOf(BatchSessions).instanceOf(id).suspend() // Manually force a process manager to suspend from the outside
// domainModel.processManagerOf(BatchSessions).instanceOf(id).resume() // Forces a suspended process manager to resume processing from where it was suspended
// domainModel.processManagerOf(BatchSessions).instanceOf(id).resumeFrom(sequenceNumber) // Forces a suspended process manager to resume processing from a given future sequence number

//
// It's up tp system implementors to decide when it's appropriate to clean up old process managers - i.e. they can look for states they know are finished/done states
//
// For Events being emitted:

// Sets correlation id as the id of this process manager
// By default sets, the event id as event id of the processed event + type of emitted event (default 1:1 mapping between incoming event and outgoing event type)
// Don't try to send same event type multiple times as part of same incoming event
//
// For commands being sent:
//
// Default uses eventId as commandId sent to Aggregate
//
// Need to decide how to handle commands. Should enter failure state if
//
// When a PM enters its endsWith state, the min_sequence_number is set to the last_processed_sequence_number + 1 - this essentially resets
// the PM back to eden state so that it will always start from min_sequence_number in the future rather than right from beginning of time.
// This is to deal with what can technically be indefinite running processes where a particular correlation id may continue to be used
// in the future. It's an optimisation that means the same correlation id can be reused without having to deal with historical uses of
// the same ID. A PM that has last_processed_sequence_number < min_sequence_num can technically be cleaned up as long as you're sure there won't be
// further events. At the very least, it's reasonable to delete all event history for that PM where event sequence number < min_sequence_num.
// A PM is only a candidate for needing processing if last_processed_seq_num is < max_sequence_number AND max_sequence_number >= min_sequence_number
//
// Scheduled events to send back to PM keep note of the min_sequence_number at the time the event was generated. If the min_sequence_number has
// changed by the time the PM with that correlation id has got an updated min_sequence_number, the event is not appended to the PM's event log and
// is simply discarded.
//
// Need deterministic ids for event scheduling.
// Commands will need some way to ensure deterministic ids - use incoming event id and command type?
//
// Need to introduce some kind of actor model design to restrict likelihood of trying to process a scheduled event at the same type as an external event.
// Although the philosophy of Kestrel is to expect at-least-once-delivery, it's still preferable to avoid unnecessarily causing duplicate deliveries.
//
// Scheduler needs to support the concept of acknowledgement in order to guarantee at-least-once-delivery of scheduled events.
// Need to guarantee that scheduled events will definitely be written to a process manager's event log at-least-once (with dedupe handled by event id during actual processing)
//
// Processing of a process manager should also happen within the single-threaded environment of an actor. With bounded mailbox if too many attempts to concurrently
// trigger processing occur.
//
// If you schedule event at

// Hmmmm, does it matter than events emitted by a PM may not be handled sequentially after the invocation that generated the event?
// This could be especially prevalent in cases where processing has been held up by an error and a backlog of other events has
// built up. Once event that was causing error has passed through, and say it's emitted an event, that event will be processed
// after all the events that had backed up. The issue here is that it means there is an inherent lack of determinism in
// the way PMs work - i.e. timing really matters. Maybe that's an inherent characteristic and something implementors simply have
// to be aware of and factor into implementation? Technically speaking, there's no way round this - one could never predict
// the precise ordering of events being received by a PM.
//
// It's important to bear in mind that emitting events in the case of PMs isn't the same as emitting events in the case
// of aggregates. It's not quite the equivalent in that the state is based off of the incoming stream of events and events
// emitted are seen as triggering some future action, not something that represents a way of persisting current state.

// Should be possible to determine how to handle commandhandlingresponses. There are some circumstances where a command
// would never succeed and the process manager needs to be able to define internally how to respond to such scenarios.

// Is there a way to ensure that events scheduled to be delivered back to a PM itself can be more accurately handled
// close to the intended time? e.g. in the event of errors trying to send a command, a future event may only be scheduled
// when the command is finally sent. Problem is, the event won't be scheduled to happen relative to when it would otherwise
// have happened had the command be dispatched successfully on first attempt. In the meantime, further events may have
// been backing up to be processed which will be processed before the future scheduled event, despite the fact that, had
// the command dispatch happened first time, the scheduled event would have been received before.

// There's a potential argument for a new approach where events that are actually handled are written to a separate
// event log to the event log where a PM's unprocessed event backlog is building up. And there could also be a special
// backlog for events specifically scheduled by the PM to itself. When processing the main backlog, could check for
// any events in the special backlog that have an earlier timestamp than the next one in the main backlog - if so, the
// scheduled event will be handled first.

interface BatchSessionsContext : ProcessManagerContext {

    data class CarPark(val priceCappingPeriod: Duration)

    val carPark: CarPark
}

// Events
sealed class BatchSessionsEvent : DomainEvent { override val tag = DomainEventTag("batch-parking-sessions-event") }

data class ParkingSessionQueued(
    val carParkId: AggregateId,
    val parkingAccountId: AggregateId,
    val parkableVehicleId: AggregateId,
    val startedAt: Instant,
    val finishedAt: Instant): BatchSessionsEvent()

data class ParkingSessionBatchCreated(
    val carParkId: AggregateId,
    val vehicle: Vehicle,
    val sessions: List<QueuedParkingSession>): BatchSessionsEvent()

object BufferingPeriodEnded: BatchSessionsEvent()
data class TimedOutCreatingBatch(val completedBatchId: AggregateId): BatchSessionsEvent()

// States
sealed class BatchSessionsState : ProcessManagerState

object Empty : BatchSessionsState()
object Finished: BatchSessionsState()

data class Buffering(
        val carParkId: AggregateId,
        val vehicle: Vehicle,
        val buffer: ParkingSessionBuffer): BatchSessionsState() {

    val bufferedSessions = buffer.sessions

    operator fun plus(queuedParkingSession: QueuedParkingSession) = copy(buffer = buffer + queuedParkingSession)

    fun isWithinBufferingPeriod(timestamp: Instant) = buffer.bufferingWillCompleteAt.isAfter(timestamp)
}

data class CreatingBatch(
        val carParkId: AggregateId,
        val vehicle: Vehicle,
        val completedBatchId: AggregateId,
        val completedBatch: List<QueuedParkingSession>,
        val futureBuffer: ParkingSessionBuffer? = null,
        val completedFutureBatches: List<List<QueuedParkingSession>> = emptyList()): BatchSessionsState() {

    fun addToFutureBuffer(session: QueuedParkingSession, carPark: BatchSessionsContext.CarPark): CreatingBatch {
        // TODO: Add completed future batch if necessary
        val newFutureBuffer = futureBuffer?: ParkingSessionBuffer.startNew(session, carPark)
        return copy(futureBuffer = newFutureBuffer)
    }

    fun withNextFutureBatch(): CreatingBatch =
        copy(
            completedBatchId = AggregateId(), // TODO: create deterministic (but unique to this batch) ID
            completedBatch = completedFutureBatches.first(),
            completedFutureBatches = completedFutureBatches.drop(1)
        )

    val isBufferingFutureSessions = futureBuffer?.sessions?.isNotEmpty() ?: false

    val containsCompletedFutureBatches = completedFutureBatches.isNotEmpty() // TODO: Implement
}

// Value Objects
data class Vehicle(val parkingAccountId: AggregateId, val parkableVehicleId: AggregateId)
data class QueuedParkingSession(val startedAt: Instant, val finishedAt: Instant)

data class ParkingSessionBuffer(val sessions: List<QueuedParkingSession>, val bufferingStartedAt: Instant, val bufferingWillCompleteAt: Instant) {

    operator fun plus(queuedParkingSession: QueuedParkingSession) = copy(sessions = sessions + queuedParkingSession)

    companion object {
        fun startNew(parkingSession: QueuedParkingSession, carPark: BatchSessionsContext.CarPark) =
            ParkingSessionBuffer(
                sessions = listOf(parkingSession),
                bufferingStartedAt = parkingSession.startedAt,
                bufferingWillCompleteAt = parkingSession.startedAt + carPark.priceCappingPeriod
            )
    }
}

object BatchSessions: ProcessManager<BatchSessionsContext, BatchSessionsEvent, BatchSessionsState> {

    override val blueprint =

        processManager("batch-parking-sessions", startWith = Empty, endWith = Finished) {

            behaviour<Empty> {

                event<ParkingSessionQueued> { cxt, _, evt ->
                    goto(Buffering(
                            carParkId = evt.carParkId,
                            vehicle = Vehicle(
                                parkingAccountId = evt.parkingAccountId,
                                parkableVehicleId = evt.parkableVehicleId
                            ),
                            buffer = ParkingSessionBuffer.startNew(
                                carPark = cxt.carPark,
                                parkingSession = QueuedParkingSession(
                                    startedAt = evt.startedAt,
                                    finishedAt = evt.finishedAt
                                )
                            ))
                    ){ "dreweaster" to "password" }.andSend( { RegisterUser(it.first,it.second) toAggregate User identifiedBy AggregateId()})
                    .andEmit({ BufferingPeriodEnded at evt.startedAt + cxt.carPark.priceCappingPeriod })
                }

                commandErrorHandler<RegisterUser, AggregateInstanceAlreadyExists> { ex, cmd, aggregateType, aggregateId ->
                    // Return list of events to emit to self or empty list if error can be ignored.
                }
            }

            behaviour<Buffering> {

                event<BufferingPeriodEnded> { _, state, _ ->
                    val completedBatchId = AggregateId() // TODO: create deterministic (but unique to this batch) ID
                    goto(CreatingBatch(
                            carParkId = state.carParkId,
                            vehicle = state.vehicle,
                            completedBatchId = completedBatchId,
                            completedBatch = state.bufferedSessions)
                    ) { "dreweaster" to "password"  }.andSend(
                        { RegisterUser("dreweaster", "password") toAggregate User identifiedBy AggregateId() },
                        { RegisterUser("dreweaster", "password") toAggregate User identifiedBy AggregateId() }
                    ).andEmit (
                        { TimedOutCreatingBatch(completedBatchId) after 2.minutes() }
                    )
                }

                event<ParkingSessionQueued> { cxt, state, evt ->
                    when {
                        state.isWithinBufferingPeriod(evt.finishedAt) ->
                            goto(state + QueuedParkingSession(startedAt = evt.startedAt, finishedAt = evt.finishedAt))
                        else -> {
                            val completedBatchId = AggregateId() // TODO: create deterministic (but unique to this batch) ID
                            goto(CreatingBatch(
                                    carParkId = state.carParkId,
                                    vehicle = state.vehicle,
                                    completedBatchId = completedBatchId,
                                    completedBatch = state.buffer.sessions,
                                    futureBuffer =
                                        ParkingSessionBuffer.startNew(
                                            carPark = cxt.carPark,
                                            parkingSession = QueuedParkingSession(
                                                startedAt = evt.startedAt,
                                                finishedAt = evt.finishedAt
                                            )
                                        )
                            )
                            ).andSend (
                                { RegisterUser("dreweaster", "password") toAggregate User identifiedBy AggregateId()}
                            ).andEmit (
                                { TimedOutCreatingBatch(completedBatchId) after 2.minutes() }
                            )
                        }
                    }
                }
            }

            behaviour<CreatingBatch> {

                event<TimedOutCreatingBatch> { _, state, evt ->
                    when {
                        state.completedBatchId != evt.completedBatchId -> goto(state) // TODO: Is this the correct way to ignore an event?
                        else -> throw Suspend("failed_to_create_batch")
                    }
                }

                // TODO: Handle case where receiving TimedOutCreatingBatch for an old batch whilst creating a more recent one
                // Should just ignore it as we'd not have been able to advance to a new batch if the older batch hadn't been created successfully

                event<ParkingSessionQueued> { cxt, state, evt ->
                    goto(state.addToFutureBuffer(
                        session = QueuedParkingSession(startedAt = evt.startedAt, finishedAt = evt.finishedAt),
                        carPark = cxt.carPark)
                    )
                }

                // TODO: Should this double check the id is as expected? Raise an error just in case? If not sure, let someone resolve it manually (Greg Young)
                event<ParkingSessionBatchCreated> { _, state, _ ->
                    when {
                        state.containsCompletedFutureBatches -> {
                            val nextState = state.withNextFutureBatch()
                            goto(nextState)
                                .andSend ({ RegisterUser("", "") toAggregate User identifiedBy AggregateId() })
                                .andEmit ({ TimedOutCreatingBatch(nextState.completedBatchId) after 2.minutes() })
                        }
                        state.isBufferingFutureSessions -> {
                            goto(Buffering(
                                    carParkId = state.carParkId,
                                    vehicle = state.vehicle,
                                    buffer = state.futureBuffer!!)
                            ).andEmit ({ BufferingPeriodEnded at state.futureBuffer.bufferingWillCompleteAt })
                        }
                        else -> goto(Finished)
                    }
                }
            }
        }
}

class ProcessManagerEntryPoint<C: ProcessManagerContext, E: DomainEvent, S: ProcessManagerState> (
        private val processManagerType: ProcessManager<C, E, S>,
        private val processManagerId: String,
        private val commandDispatcher: CommandDispatcher,
        private val eventScheduler: EventScheduler) {

    suspend fun process() {
        val blueprint = processManagerType.blueprint
        val behaviour = blueprint.capturedBehaviours[blueprint.startWith::class]
        val event = ParkingSessionQueued(AggregateId(), AggregateId(), AggregateId(), Instant.now(), Instant.now()) as E
        val state = blueprint.startWith
        val batchSessionsContext = object : BatchSessionsContext { override val carPark = BatchSessionsContext.CarPark(Duration.ofMinutes(100)) } as C

        val handler = behaviour?.capturedHandlers?.get(event::class)!! as (C,S,E) -> ProcessManagerStepBuilder<*,C,E,S>
        val builder = handler.invoke(batchSessionsContext, state, event)

        val executedStep = builder.execute()

        // TODO: Need to ideally dispatch commands and schedule events in parallel and then compose results
        when(executedStep) {
            is SuccessfullyExecutedStep -> {
                executedStep.sendableCommands.forEach {
                    it.sendUsing(commandDispatcher)
                }
                executedStep.scheduledEvents.forEach {
                    it.scheduleUsing(eventScheduler)
                }
            }
        }
    }
}

data class ProcessManagerScheduledEvent<C: ProcessManagerContext, E: DomainEvent, S: ProcessManagerState>(val serialisedEvent: String, val metadata: ProcessManagerScheduledEventMetadata<C,E,S>) {

    data class ProcessManagerScheduledEventMetadata<C: ProcessManagerContext, E: DomainEvent, S: ProcessManagerState>(
            val processManagerType: ProcessManager<C, E, S>,
            val processManagerId: AggregateId,
            val eventId: EventId,
            val eventVersion: Long)
}

interface ProcessManagerEventScheduler {

    interface ProcessManagerScheduledEventNotification<C: ProcessManagerContext, E: DomainEvent, S: ProcessManagerState> {
        val event: ProcessManagerScheduledEvent<C,E,S>
        fun ack() // It's necessary for a listener to call ack() to confirm event has been handled. Otherwise scheduler should resend
    }

    interface Listener<C: ProcessManagerContext, E: DomainEvent, S: ProcessManagerState> { fun onEventTriggered(notification: ProcessManagerScheduledEventNotification<C,E,S>) }

    fun <C: ProcessManagerContext, E: DomainEvent, S: ProcessManagerState> registerListener(listener: Listener<C,E,S>) // DomainModel impl will attach as listener and handle event serialisation/deserialisation

    fun <C: ProcessManagerContext, E: DomainEvent, S: ProcessManagerState> schedule(event: ProcessManagerScheduledEvent<C,E,S>, after: Instant)
}

interface DomainModel {

    // By listening in to this event, one can choose to automatically trigger the process manager to event if desired
    // domainModel.processManagerOf(BatchSessions).instanceOf(processManagerId).event()
    // Alternative is to solely rely on polling domainModel.processManagerOf(BatchSessions).instancesAwaitingProcessing(pageable): Page<ProcessManagerId>
    // As a way to trigger the processing of process managers with outstanding events to event
    // Not guaranteed delivery so still would need the separate event that's polling domainModel.processManagerOf(BatchSessions).instancesAwaitingProcessing(pageable)
    // Only works if current app instance is the one that persisted the event
    interface ProcessManagerListener<C: ProcessManagerContext, E: DomainEvent, S: ProcessManagerState> { fun onProcessManagerEventPersisted(processManagerType: ProcessManager<C,E,S>, processManagerId: AggregateId, event: E) }

    fun <C: ProcessManagerContext, E: DomainEvent, S: ProcessManagerState> addProcessManagerListener(processManagerType: ProcessManager<C,E,S>, listener: ProcessManagerListener<C,E,S>)
}

fun main(args: Array<String>) {

    fun <C: ProcessManagerContext, E: DomainEvent, S: ProcessManagerState> processManagerOf(
            processManagerType: ProcessManager<C, E, S>,
            processManagerId: String): ProcessManagerEntryPoint<C,E,S> {

        val domainModel = EventSourcedDomainModel(InMemoryBackend(), TwentyFourHourWindowCommandDeduplication)
        val commandDispatcher = object : CommandDispatcher {
            override suspend fun <C : DomainCommand, E : DomainEvent, S : AggregateState> dispatch(command: C, aggregateType: Aggregate<C, E, S>, aggregateId: AggregateId): Try<Unit> {
                // TODO: Stuff like generating the right metadata
                val result = domainModel.aggregateRootOf(aggregateType, aggregateId).handleCommand(command)
                return when(result) {
                    is SuccessResult -> Try.success(Unit)
                    is RejectionResult -> Try.failure(result.error) // TODO: Probably terminal so suspend the PM
                    is ConcurrentModificationResult -> Try.failure(OptimisticConcurrencyException) // TODO: Retry a few times before suspending
                    is UnexpectedExceptionResult -> Try.failure(result.ex) // TODO: Retry a few times before suspending
                }
            }
        }

        val eventScheduler = object : EventScheduler {
            override suspend fun <Evt : E, E : DomainEvent> schedule(event: Evt, at: Instant) = Try.success(Unit)
        }
        return ProcessManagerEntryPoint(processManagerType, processManagerId, commandDispatcher, eventScheduler)
    }

    val pm = processManagerOf(BatchSessions, "")
    runBlocking { pm.process() }
}