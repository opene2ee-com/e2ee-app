package com.opene2ee.opene2ee.vpn.net

import java.util.concurrent.ConcurrentHashMap

abstract class SessionStore<K, S : Session> {
    private val sessions: MutableMap<K, S> = ConcurrentHashMap(128)
    internal fun insertSession(key: K, session: S) { sessions[key] = session }
    internal fun query(key: K): S? = sessions[key]
}
