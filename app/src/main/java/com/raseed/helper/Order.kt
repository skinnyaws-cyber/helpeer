package com.raseed.helper

import com.google.firebase.Timestamp

data class Order(
    val id: String = "",
    val amount: Double = 0.0,
    val commission: Double = 0.0,
    val deviceType: String = "",
    val receivingCard: String = "",
    val status: String = "",
    val targetInfo: String = "",
    val telecomProvider: String = "",
    val transferType: String = "",
    val userFullName: String = "",
    val userId: String = "",
    val userPhone: String = "",
    val timestamp: Timestamp? = null
)
