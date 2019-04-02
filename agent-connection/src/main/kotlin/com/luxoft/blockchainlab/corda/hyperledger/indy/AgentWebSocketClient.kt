package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.fasterxml.jackson.databind.node.ObjectNode
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import mu.KotlinLogging
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import rx.*
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class AgentWebSocketClient(serverUri: URI, private val socketName: String) : WebSocketClient(serverUri) {
    private val log = KotlinLogging.logger {}

    override fun onOpen(handshakedata: ServerHandshake?) {
        log.info { "$socketName:AgentConnection opened: $handshakedata" }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        log.info { "$socketName:AgentConnection closed: $code,$reason,$remote" }
    }

    /**
     * Inbound messages indexed by (subscription) key, double linked queue for each key.
     */
    private val receivedMessages = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()

    /**
     * message routing map, indexed by subscription key (message-specific), double linked queue of observers
     * for each (message-specific) key
     */
    private val subscribedObservers = ConcurrentHashMap<String, ConcurrentLinkedQueue<SingleSubscriber<in String>>>()

    /**
     * Adds an observer to (FIFO) queue corresponding to the given key.
     * If the queue doesn't exist for the given key, it's been created.
     */
    private fun addObserver(key: String, observer: SingleSubscriber<in String>) =
            subscribedObservers.getOrPut(key) { ConcurrentLinkedQueue() }.add(observer)

    /**
     * Adds a message to the queue corresponding to the given key.
     * If the queue doesn't exist for the given key, it's been created.
     */
    private fun storeMessage(key: String, message: String) =
            receivedMessages.getOrPut(key) { ConcurrentLinkedQueue() }.add(message)

    /**
     * Removes an observer from the queue.
     */
    private fun popObserver(key: String) =
            subscribedObservers.getOrPut(key) { ConcurrentLinkedQueue() }.poll()

    /**
     * Pops a message by key from the queue.
     */
    private fun popMessage(key: String) =
            receivedMessages.getOrPut(key) { ConcurrentLinkedQueue() }.poll()

    /**
     * Dispatches the message to an observer.
     * Each message '@type' has own routing agreement
     */
    override fun onMessage(msg: String?) {
        var message = msg
        log.info { "$socketName:ReceivedMessage: $message" }
        if (message == null) {
            log.error { "$socketName: Null message received" }
            return
        }
        val obj = SerializationUtils.jSONToAny<ObjectNode>(message)
        val type: String = obj["@type"].asText()
        var key: String? = null
        when (type) {
            MESSAGE_TYPES.MESSAGE_RECEIVED -> {
                /**
                 * Object messages are routed by the object class name + sender DID
                 */
                val msgReceived = SerializationUtils.jSONToAny<MessageReceived>(message)
                val className = msgReceived.message.content.clazz
                val serializedObject = msgReceived.message.content.message
                val fromDid = msgReceived.message.from
                key = "$className.$fromDid"
                message = SerializationUtils.anyToJSON(serializedObject)
            }
            MESSAGE_TYPES.INVITE_RECEIVED ->
                /**
                 * 'invite_received' message is routed by type + public key
                 */
                key = "$type.${obj["key"].asText()}"
            MESSAGE_TYPES.RESPONSE_RECEIVED ->
                /**
                 * 'response_received' message is routed by type + public key
                 */
                key = "$type.${obj["history"]["connection~sig"]["signer"].asText()}"
            MESSAGE_TYPES.RESPONSE_SENT ->
                /**
                 * 'response_sent' message is routed by type + other party's DID
                 */
                key = "$type.${obj["did"].asText()}"
        }
        val k = key ?: type
        /**
         * select the first message observer from the list, which must be non-empty,
         * remove the observer from the queue, emit the serialized message
         */
        val observer = popObserver(k)
        if (observer != null) {
            observer.onSuccess(message)
            return
        }
        storeMessage(k, message)
    }

    override fun onError(ex: Exception?) {
        log.warn(ex) { "AgentConnection error" }
    }

    /**
     * Sends an object (JSON-serialized) to WebSocket
     */
    fun sendAsJson(obj: Any) {
        val message = SerializationUtils.anyToJSON(obj)
        log.info { "$socketName:SendMessage: $message" }
        send(message)
    }

    /**
     * Receives a message of the given type and key, deserialize it and emit the resulting object
     */
    fun <T : Any> receiveMessageOfType(type: String, key: String? = null, className: Class<T>): Single<T> {
        return Single.create { observer ->
            try {
                popMessageOfType(type, key).subscribe({ message ->
                    val typedMessage: T = SerializationUtils.jSONToAny(message, className)
                    observer.onSuccess(typedMessage)
                }, { e: Throwable -> observer.onError(e) })
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }

    inline fun <reified T : Any> receiveMessageOfType(type: String, key: String? = null): Single<T> = receiveMessageOfType(type, key, T::class.java)

    /**
     * Receives a serialized object from another IndyParty (@from), deserialize it and emit the result
     */
    fun <T : Any> receiveClassObject(className: Class<T>, from: IndyParty): Single<T> {
        return Single.create { observer ->
            try {
                popClassObject(className, from).subscribe({ objectJson ->
                    val classObject: T = SerializationUtils.jSONToAny(objectJson, className)
                    observer.onSuccess(classObject)
                }, { e: Throwable -> observer.onError(e) })
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }

    inline fun <reified T : Any> receiveClassObject(from: IndyParty) = receiveClassObject(T::class.java, from)

    fun sendClassObject(message: TypedBodyMessage, counterParty: IndyParty) = sendAsJson(SendMessage(counterParty.did, message))

    inline fun <reified T : Any> sendClassObject(message: T, counterParty: IndyParty) = sendClassObject(TypedBodyMessage(message, T::class.java.canonicalName), counterParty)

    /**
     * Pops message by key, subscribes on such message, if the queue is empty
     */
    private fun popMessage(keyOrType: String, observer: SingleSubscriber<in String>) {
        val message = popMessage(keyOrType)
        if (message != null) {
            /**
             * if found, remove it from the queue and emit the JSON-serialized message/object
             */
            observer.onSuccess(message)
        } else {
            /**
             * otherwise, subscribe on messages with the given key
             */
            addObserver(keyOrType, observer)
        }
    }

    /**
     * Subscribes on a message of the given type and key, emits a Single<> serialized message
     */
    private fun popMessageOfType(type: String, key: String? = null): Single<String> {
        return Single.create { observer ->
            try {
                popMessage(if (key != null) "$type.$key" else type, observer)
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }

    /**
     * Subscribes on a message containing an object coming from another IndyParty (@from)
     * Emits a Single<> serialized object
     */
    private fun <T : Any> popClassObject(className: Class<T>, from: IndyParty): Single<String> {
        return Single.create { observer ->
            try {
                popMessage("${className.canonicalName}.${from.did}", observer)
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }
}

