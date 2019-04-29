# Java version of flowing-retail/rest

This example demonstrates stateful resilience patterns in a REST environment. A payment (micro-)service retrieves payments if called via REST. It requires an upstream REST service to charge credit cards.

![REST callstack](https://raw.githubusercontent.com/berndruecker/flowing-retail/master/docs/resilience-patterns/situation.png)

This simple call-chain is great to demonstrate resilience patterns.

# Concrete technologies/frameworks in this example:

* Java 8
* Spring Boot 1.5.x
* Hystrix
* Spring State Machine

# How-to run

First you have to checkout and run the [stripe fake server](https://github.com/berndruecker/flowing-retail/tree/master/rest/java/stripe-fake), as this handles the credit card payments.

```
mvn -f ../stripe-fake/ exec:java
```

Now you can run the payment service itself

```
mvn exec:java
```

Now the different versions of the payment service are available:

* http://localhost:8100/api/payment/v1
* ...
* http://localhost:8100/api/payment/v3

You now can issue a PUT with an empty body:

```
curl \
-H "Content-Type: application/json" \
-X PUT \
-d '{}' \
http://localhost:8100/api/payment/v1
```

