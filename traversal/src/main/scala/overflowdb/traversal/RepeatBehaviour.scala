package overflowdb.traversal

trait RepeatBehaviour[A] { this: EmitBehaviour =>
  val untilCondition: Option[A => Boolean]
  val times: Option[Int]

  def timesReached(currentDepth: Int): Boolean =
    times.isDefined && times.get <= currentDepth
}

sealed trait EmitBehaviour
trait EmitNothing extends EmitBehaviour
trait EmitAll extends EmitBehaviour
trait EmitAllButFirst extends EmitBehaviour
trait EmitConditional[A] extends EmitBehaviour {
  def emit(a: A): Boolean
}

object RepeatBehaviour {

  def noop[A](builder: RepeatBehaviour.Builder[A]): Builder[A] = builder

  class Builder[A] {
    private[this] var _emitNothing: Boolean = true
    private[this] var _emitAll: Boolean = false
    private[this] var _emitAllButFirst: Boolean = false
    private[this] var _emitCondition: Option[A => Boolean] = None
    private[this] var _untilCondition: Option[A => Boolean] = None
    private[this] var _times: Option[Int] = None

    /* configure `repeat` step to emit everything along the way */
    def emit: Builder[A] = {
      _emitNothing = false
      _emitAll = true
      _emitAllButFirst = false
      _emitCondition = Some(_ => true)
      this
    }

    /* configure `repeat` step to emit everything along the way, apart from the _first_ element */
    def emitAllButFirst: Builder[A] = {
      _emitNothing = false
      _emitAll = false
      _emitAllButFirst = true
      _emitCondition = Some(_ => true)
      this
    }

    /* configure `repeat` step to emit whatever meets the given condition */
    def emit(condition: A => Boolean): Builder[A] = {
      _emitNothing = false
      _emitAll = false
      _emitAllButFirst = false
      _emitCondition = Some(condition)
      this
    }

    /* configure `repeat` step to stop traversing when given condition is true */
    def until(condition: A => Boolean): Builder[A] = {
      _untilCondition = Some(condition)
      this
    }

    def times(value: Int): Builder[A] = {
      _times = Some(value)
      this
    }

    private[traversal] def build: RepeatBehaviour[A] = {
      if (_emitNothing) {
        new RepeatBehaviour[A] with EmitNothing {
          override final val untilCondition: Option[A => Boolean] = _untilCondition
          final override val times: Option[Int] = _times
        }
      } else if (_emitAll) {
        new RepeatBehaviour[A] with EmitAll {
          override final val untilCondition: Option[A => Boolean] = _untilCondition
          final override val times: Option[Int] = _times
        }
      } else if (_emitAllButFirst) {
        new RepeatBehaviour[A] with EmitAllButFirst {
          override final val untilCondition: Option[A => Boolean] = _untilCondition
          final override val times: Option[Int] = _times
        }
      } else {
        val __emitCondition = _emitCondition
        new RepeatBehaviour[A] with EmitConditional[A] {
          override final val untilCondition: Option[A => Boolean] = _untilCondition
          final private val _emitCondition = __emitCondition.get
          override final def emit(a: A): Boolean = _emitCondition(a)
          final override val times: Option[Int] = _times
        }
      }
    }
  }

}
