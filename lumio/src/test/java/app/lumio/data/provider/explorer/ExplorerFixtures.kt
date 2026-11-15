package app.lumio.data.provider.explorer

/** An Etherscan-family answer: the status, the message, and `result` as the JSON text that the caller gives. */
fun explorerAnswer(status: String, message: String, result: String): String = """{"status":"$status","message":"$message","result":$result}"""

/**
 * One normal transaction in the shape the docs give for `txlist`. The values are the ones a live answer uses, and [value] and [input] can be set for the
 * case under test.
 */
fun normalRow(
    hash: String,
    from: String,
    to: String,
    value: String = "1000000000000000000",
    isError: String = "0",
    input: String = "0x",
    contractAddress: String = "",
): String = """{"hash":"$hash","timeStamp":"1693526411","from":"$from","to":"$to","value":"$value","isError":"$isError","input":"$input","contractAddress":"$contractAddress","gasUsed":"21000","gasPrice":"20000000000","confirmations":"1000","blockNumber":"18000000","blockHash":"0x${"11".repeat(32)}","nonce":"5","transactionIndex":"1","gas":"21000","cumulativeGasUsed":"21000","txreceipt_status":"1","methodId":"0x","functionName":""}"""

/** One ERC-20 transfer in the shape the docs give for `tokentx`, of the USDC contract `0xee..`, with [amount] base units and [decimals] decimals. */
fun tokenRow(
    hash: String,
    from: String,
    to: String,
    amount: String = "1500000",
    decimals: String = "6",
): String = """{"hash":"$hash","timeStamp":"1693526411","from":"$from","to":"$to","contractAddress":"0x${"ee".repeat(20)}","value":"$amount","tokenName":"USD Coin","tokenSymbol":"USDC","tokenDecimal":"$decimals","confirmations":"900","blockNumber":"18000000","blockHash":"0x${"11".repeat(32)}","nonce":"1","transactionIndex":"2","gas":"60000","gasPrice":"1","gasUsed":"50000","cumulativeGasUsed":"100000","input":"deprecated","methodId":"0xa9059cbb","functionName":"transfer(address _to, uint256 _value)"}"""
