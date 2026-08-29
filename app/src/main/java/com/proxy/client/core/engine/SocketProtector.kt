package com.proxy.client.core.engine

fun interface SocketProtector {
    fun protect(socketFd: Int): Boolean
}
