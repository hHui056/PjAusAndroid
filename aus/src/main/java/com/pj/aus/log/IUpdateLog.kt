package com.pj.aus.log

/**
 * Create By hHui on 2026/6/10 13:41
 *
 * @description
 */
interface IUpdateLog {
    fun v(tag: String, message: String)
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String)
}