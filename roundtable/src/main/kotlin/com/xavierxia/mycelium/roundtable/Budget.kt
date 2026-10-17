package com.xavierxia.mycelium.roundtable

/** Prices are US dollars per one million tokens, checked against DeepSeek's published table. */
data class ModelPrices(val inputPerMillion: Double, val outputPerMillion: Double)

data class ModelSettings(
    val baseUrl: String,
    val model: String,
    val prices: ModelPrices,
    val monthlyLimitUsd: Double,
    val apiKey: String?,
)

data class CostEstimate(val inputTokens: Int, val outputTokens: Int, val usd: Double)

private const val OUTPUT_TOKEN_ALLOWANCE = 2_000

/** A ceiling, not a tokenizer: four characters per token over-estimates Chinese and English. */
fun estimateCost(promptCharacters: Int, prices: ModelPrices): CostEstimate {
    val inputTokens = (promptCharacters + 3) / 4
    val usd = inputTokens * prices.inputPerMillion / 1_000_000.0 +
        OUTPUT_TOKEN_ALLOWANCE * prices.outputPerMillion / 1_000_000.0
    return CostEstimate(inputTokens, OUTPUT_TOKEN_ALLOWANCE, usd)
}

/** Actual cost from a completed response. Cache hits are billed at the input price too. */
fun actualCost(promptTokens: Int, completionTokens: Int, prices: ModelPrices): Double =
    promptTokens * prices.inputPerMillion / 1_000_000.0 +
        completionTokens * prices.outputPerMillion / 1_000_000.0

class BudgetExceeded(message: String) : Exception(message)

fun requireWithinBudget(spentUsd: Double, estimate: CostEstimate, limitUsd: Double) {
    if (spentUsd + estimate.usd > limitUsd) {
        throw BudgetExceeded("本月费用估算会超过上限 ${"%.2f".format(limitUsd)} 美元")
    }
}
