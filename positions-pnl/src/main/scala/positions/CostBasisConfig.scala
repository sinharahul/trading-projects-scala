package positions

object CostBasisConfig {
  // In production this comes from a reference-data table.
  private val methodsByAccount: Map[String, CostBasisMethod] = Map(
    "ACC-1001" -> FifoMethod,
    "ACC-1002" -> AverageCostMethod
  )
  def methodFor(account: String): CostBasisMethod =
    methodsByAccount.getOrElse(account, AverageCostMethod)
}
