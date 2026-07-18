package com.opene2ee.e2ee_ap_v2.vpn.kernel.annotation

/**
 * 标记一个功能是实验性的，可能存在漏洞
 * */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD)
annotation class Experimental
