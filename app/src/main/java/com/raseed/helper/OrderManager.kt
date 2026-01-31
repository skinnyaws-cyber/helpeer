package com.raseed.helper

import android.util.Log

object OrderManager {
    val pendingDirectOrders = mutableListOf<Order>()

    fun addOrder(order: Order) {
        if (pendingDirectOrders.none { it.id == order.id }) {
            pendingDirectOrders.add(order)
            Log.d("OrderManager", "Added to watchlist: ${order.amount}")
        }
    }

    fun findMatchingOrder(smsAmount: Double, smsSenderPhone: String, provider: String): Order? {
        return pendingDirectOrders.find { order ->
            val providerMatch = order.telecomProvider.contains(provider, ignoreCase = true)
            val amountMatch = order.amount == smsAmount
            
            val cleanOrderPhone = normalizePhone(order.userPhone)
            val cleanSmsPhone = normalizePhone(smsSenderPhone)
            val phoneMatch = cleanOrderPhone.endsWith(cleanSmsPhone) || cleanSmsPhone.endsWith(cleanOrderPhone)

            providerMatch && amountMatch && phoneMatch
        }
    }
    
    private fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    fun removeOrder(orderId: String) {
        pendingDirectOrders.removeAll { it.id == orderId }
    }
}
