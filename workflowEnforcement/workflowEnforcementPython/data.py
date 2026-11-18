"""Mock back-end data for an online clothing shop (made up for the exercise)."""

CUSTOMERS = {
    "C-1001": {
        "customer_id": "C-1001",
        "name": "Maria Lopez",
        "email": "maria.lopez@example.com",
        "identity_verified": True,
        "shipping_address": "4 Harbour Road, Bristol BS1 5TT",
        "loyalty_points": 1240,
    },
    "C-1002": {
        "customer_id": "C-1002",
        "name": "James Chen",
        "email": "james.chen@example.com",
        # Account flagged: ownership could not be confirmed. The gate must refuse refunds here.
        "identity_verified": False,
        "shipping_address": "77 Mill Lane, Leeds LS2 7AB",
        "loyalty_points": 310,
    },
}

ORDERS = {
    "O-5001": {
        "order_id": "O-5001",
        "customer_id": "C-1001",
        "item": "Wool overcoat (navy, M)",
        "amount": 129.00,
        "status": "delivered",
        "days_since_delivery": 9,
        "charges": [129.00],
    },
    "O-5002": {
        "order_id": "O-5002",
        "customer_id": "C-1001",
        "item": "Canvas sneakers (white, 38)",
        "amount": 89.50,
        "status": "delivered",
        "days_since_delivery": 20,
        "charges": [89.50, 89.50],  # charged twice: a billing dispute
    },
    "O-5003": {
        "order_id": "O-5003",
        "customer_id": "C-1002",
        "item": "Linen shirt (white, L)",
        "amount": 45.00,
        "status": "delivered",
        "days_since_delivery": 3,
        "charges": [45.00],
    },
}
