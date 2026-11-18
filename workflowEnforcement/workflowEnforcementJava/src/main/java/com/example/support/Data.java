package com.example.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mock back-end data for an online clothing shop (made up for the exercise). */
public final class Data {

    private Data() {
    }

    public record Customer(String customerId, String name, String email, boolean identityVerified,
                           String shippingAddress, int loyaltyPoints) {

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("customer_id", customerId);
            m.put("name", name);
            m.put("email", email);
            m.put("identity_verified", identityVerified);
            m.put("shipping_address", shippingAddress);
            m.put("loyalty_points", loyaltyPoints);
            return m;
        }
    }

    public record Order(String orderId, String customerId, String item, double amount, String status,
                        int daysSinceDelivery, List<Double> charges) {

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("order_id", orderId);
            m.put("customer_id", customerId);
            m.put("item", item);
            m.put("amount", amount);
            m.put("status", status);
            m.put("days_since_delivery", daysSinceDelivery);
            m.put("charges", charges);
            return m;
        }
    }

    public static final Map<String, Customer> CUSTOMERS = Map.of(
            "C-1001", new Customer("C-1001", "Maria Lopez", "maria.lopez@example.com", true,
                    "4 Harbour Road, Bristol BS1 5TT", 1240),
            // Account flagged: ownership could not be confirmed. The gate must refuse refunds here.
            "C-1002", new Customer("C-1002", "James Chen", "james.chen@example.com", false,
                    "77 Mill Lane, Leeds LS2 7AB", 310));

    public static final Map<String, Order> ORDERS = Map.of(
            "O-5001", new Order("O-5001", "C-1001", "Wool overcoat (navy, M)", 129.00, "delivered", 9, List.of(129.00)),
            // charged twice: a billing dispute
            "O-5002", new Order("O-5002", "C-1001", "Canvas sneakers (white, 38)", 89.50, "delivered", 20, List.of(89.50, 89.50)),
            "O-5003", new Order("O-5003", "C-1002", "Linen shirt (white, L)", 45.00, "delivered", 3, List.of(45.00)));
}
