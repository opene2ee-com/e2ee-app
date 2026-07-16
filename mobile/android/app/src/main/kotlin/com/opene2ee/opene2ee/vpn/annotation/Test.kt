package com.opene2ee.opene2ee.vpn.annotation

/**
 * 标记一个功能是测试用的
 * */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD)
annotation class Test