package com.tangem.feature.safechain.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/** The JSON-RPC calls SafeChain needs, against one endpoint. Only https public RPCs; no Tangem hosts. */
internal interface EthRpc {
    val url: String
    suspend fun blockNumber(): BigInteger
    suspend fun block(number: BigInteger?): BlockHeader
    suspend fun call(to: String, data: ByteArray, block: BigInteger): ByteArray
    suspend fun balance(address: String, block: BigInteger): BigInteger
    suspend fun logs(addresses: List<String>, topics: List<String?>, from: BigInteger, to: BigInteger): List<LogEntry>
    suspend fun nonce(address: String): BigInteger
    suspend fun estimateGas(from: String, to: String, data: ByteArray): BigInteger
    suspend fun maxPriorityFee(): BigInteger
    suspend fun sendRaw(raw: ByteArray): String
    suspend fun receipt(txHash: String): Receipt?
}

internal data class BlockHeader(val number: BigInteger, val hash: String, val timestamp: Long, val baseFee: BigInteger)
internal data class LogEntry(val address: String, val topics: List<String>, val data: String, val blockNumber: BigInteger)
internal data class Receipt(val status: Boolean, val from: String, val blockNumber: BigInteger)

internal class RpcError(message: String) : Exception(message)

internal class OkHttpEthRpc(override val url: String, private val http: OkHttpClient) : EthRpc {

    private val ids = AtomicLong(1)
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun rpc(method: String, params: JsonArray): JsonElement = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ids.getAndIncrement())
            put("method", method)
            put("params", params)
        }.toString().toRequestBody(JSON_MEDIA)
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw RpcError("$method: HTTP ${res.code}")
            val obj = json.parseToJsonElement(text).jsonObject
            obj["error"]?.let { throw RpcError("$method: ${it.jsonObject["message"]?.jsonPrimitive?.content ?: it}") }
            obj["result"] ?: JsonNull
        }
    }

    private fun hex(v: BigInteger) = JsonPrimitive(Hex.fromBigInteger(v))
    private fun JsonElement.big() = Hex.toBigInteger(jsonPrimitive.content)
    private fun JsonElement.str() = jsonPrimitive.content

    override suspend fun blockNumber() = rpc("eth_blockNumber", JsonArray(emptyList())).big()

    override suspend fun block(number: BigInteger?): BlockHeader {
        val tag = number?.let { hex(it) } ?: JsonPrimitive("latest")
        val b = rpc("eth_getBlockByNumber", buildJsonArray { add(tag); add(JsonPrimitive(false)) })
        if (b is JsonNull) throw RpcError("block $number not found")
        val o = b.jsonObject
        return BlockHeader(
            number = o.getValue("number").big(),
            hash = o.getValue("hash").str().lowercase(),
            timestamp = o.getValue("timestamp").big().toLong(),
            baseFee = o["baseFeePerGas"]?.big() ?: BigInteger.ZERO,
        )
    }

    override suspend fun call(to: String, data: ByteArray, block: BigInteger): ByteArray {
        val tx = buildJsonObject { put("to", to); put("data", Hex.encode(data)) }
        return Hex.decode(rpc("eth_call", buildJsonArray { add(tx); add(hex(block)) }).str())
    }

    override suspend fun balance(address: String, block: BigInteger) =
        rpc("eth_getBalance", buildJsonArray { add(JsonPrimitive(address)); add(hex(block)) }).big()

    override suspend fun logs(addresses: List<String>, topics: List<String?>, from: BigInteger, to: BigInteger): List<LogEntry> {
        val filter = buildJsonObject {
            put("address", JsonArray(addresses.map { JsonPrimitive(it) }))
            put("topics", JsonArray(topics.map { t -> t?.let { JsonPrimitive(it) } ?: JsonNull }))
            put("fromBlock", hex(from))
            put("toBlock", hex(to))
        }
        return rpc("eth_getLogs", buildJsonArray { add(filter) }).jsonArray.map { e ->
            val o = e.jsonObject
            LogEntry(
                address = o.getValue("address").str().lowercase(),
                topics = o.getValue("topics").jsonArray.map { it.str().lowercase() },
                data = o.getValue("data").str(),
                blockNumber = o.getValue("blockNumber").big(),
            )
        }
    }

    override suspend fun nonce(address: String) =
        rpc("eth_getTransactionCount", buildJsonArray { add(JsonPrimitive(address)); add(JsonPrimitive("pending")) }).big()

    override suspend fun estimateGas(from: String, to: String, data: ByteArray): BigInteger {
        val tx = buildJsonObject { put("from", from); put("to", to); put("data", Hex.encode(data)) }
        return rpc("eth_estimateGas", buildJsonArray { add(tx) }).big()
    }

    override suspend fun maxPriorityFee() = rpc("eth_maxPriorityFeePerGas", JsonArray(emptyList())).big()

    override suspend fun sendRaw(raw: ByteArray) =
        rpc("eth_sendRawTransaction", buildJsonArray { add(JsonPrimitive(Hex.encode(raw))) }).str()

    override suspend fun receipt(txHash: String): Receipt? {
        val r = rpc("eth_getTransactionReceipt", buildJsonArray { add(JsonPrimitive(txHash)) })
        if (r is JsonNull) return null
        val o: JsonObject = r.jsonObject
        return Receipt(
            status = o.getValue("status").big() == BigInteger.ONE,
            from = o.getValue("from").str(),
            blockNumber = o.getValue("blockNumber").big(),
        )
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
