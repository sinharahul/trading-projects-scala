package positions

/** A cost-basis method: a pure function from (state, trade) to state. */
trait CostBasisMethod {
  def name: String
  def applyTrade(state: PositionState, trade: Trade): PositionState
}

object AverageCostMethod extends CostBasisMethod {
  val name = "AVERAGE_COST"
  def applyTrade(state: PositionState, trade: Trade): PositionState =
    PositionEngine.applyTradeAverageCost(state, trade)
}
