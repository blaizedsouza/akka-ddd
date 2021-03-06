package pl.newicom.dddd.process

import akka.event.LoggingAdapter
import akka.persistence.PersistentActor
import pl.newicom.dddd.aggregate.DomainEvent
import pl.newicom.dddd.persistence.PersistentActorLogging

trait SagaState[T <: SagaState[T]]

class StateTransition[S <: SagaState[S]](log: LoggingAdapter, triggeredBy: DomainEvent, fromState: S, val toState: S, action: () => Unit) {
  def execute(): S = {
    action()
    log.debug(s"Event ${triggeredBy.getClass.getSimpleName} applied: $fromState -> $toState")
    toState.asInstanceOf[S]
  }
}

trait SagaStateHandling[S <: SagaState[S]] extends SagaAbstractStateHandling {
  this: PersistentActor with PersistentActorLogging =>

  implicit def toStateTransition(state: S): StateTransition[S] =
    state(() => ())

  implicit def toCurrentStateTransition(noState: Unit): StateTransition[S] =
    toStateTransition(state)

  implicit class ToStateTransition(toState: S) {
    def apply(action: => Unit): StateTransition[S] =
      new StateTransition(log, currentEvent, currentState, toState, () => action)
  }

  type StateFunction   = PartialFunction[DomainEvent, StateTransition[S]]
  type StateMachine    = PartialFunction[S, StateFunction]

  private var currentState: S = _
  private var currentEvent: DomainEvent = _
  private var initiation: StateFunction = _

  private var stateMachine: StateMachine = _

  def state: S = Option(currentState).getOrElse(initiation(currentEvent).toState)

  override def initialized: Boolean = Option(currentState).isDefined

  class SagaBuilder(init: StateFunction) {
    def andThen(sm: StateMachine): Unit = {
      initiation = init
      stateMachine = sm
    }
  }

  def startWhen(initiation: StateFunction) = new SagaBuilder(initiation)

  def receiveEvent: ReceiveEvent = {
    case e: DomainEvent if canHandle(e) =>
      RaiseEvent(e)
    case _ =>
      DropEvent
  }

  def updateState(event: DomainEvent): Unit = {
    currentEvent = event
    val inputState = state

    def default: PartialFunction[DomainEvent, StateTransition[S]] = { case _ => inputState } // accept delivery receipt
    val stateTransition = stateMachine.applyOrElse[S, StateFunction](inputState, _ => default).applyOrElse(event, default)
    currentState = stateTransition.execute()
  }

  private def canHandle(event: DomainEvent): Boolean = {
    val fromStatePF = stateMachine.orElse[S, StateFunction]({case null => initiation})
    if (fromStatePF.isDefinedAt(currentState)) {
      fromStatePF(currentState).isDefinedAt(event)
    } else {
      log.debug(s"No transition found from state $currentState for event $event")
      false
    }

  }

  //
  // Simple DSL
  //

  def stay(): S = currentState

  def goto(nextState: S): S = nextState

}