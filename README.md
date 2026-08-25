# Distributed FinTech Transaction & Fraud Monitor

## 📌 What is this Project?
This is an enterprise-grade, distributed microservices application designed to process financial transactions and detect fraudulent activity in real-time. Instead of a single monolithic application, it is split into two independent services that communicate asynchronously.

## 🛠️ How is it Built? (Tech Stack)
* **Core Language:** Java (JDK 21)
* **Framework:** Spring Boot 3 (Spring Web, Spring Data JPA)
* **Security:** Spring Security with JSON Web Tokens (JWT) for stateless API authentication.
* **Messaging:** Apache Kafka (Embedded for local, easily swappable for Cloud Kafka) for asynchronous event-driven communication.
* **Database:** PostgreSQL (Hosted on Neon.tech Serverless Cloud).

## 🔄 The Workflow (How Data Moves)
1. **Authentication:** A client requests a JWT token from `/api/auth/login`.
2. **Transaction Entry:** The client sends a `POST` request to the **Transaction Service** with the JWT in the Authorization header.
3. **Initial Persistence:** The Transaction Service instantly saves the transaction to the PostgreSQL database with a `PENDING` status.
4. **Event Publishing:** The Transaction Service publishes a "Transaction Created" event to an Apache Kafka topic (`transaction-events`).
5. **Asynchronous Consumption:** The REST API returns a `200 OK` to the user instantly, while in the background, the **Fraud Service** constantly listens to the Kafka topic.
6. **Fraud Detection & Update:** The Fraud Service catches the new event, runs a detection algorithm (e.g., flagging amounts > $10,000), and overwrites the PostgreSQL database row with either `FRAUD_DETECTED` or `APPROVED`.

## 💡 Why is this Useful? (The Problem it Solves)
In a real-world bank (like Chase or HDFC), fraud detection algorithms are incredibly heavy and slow. If the main API had to wait for the fraud check to finish before responding to the user, the app would freeze or time out. 
This architecture solves that by using **Event-Driven Microservices**. The API accepts the money instantly (fast for the user), and the heavy lifting is offloaded to a background service via Kafka.

## 🚀 How it Impacts the Business (And Your Resume)
* **High Scalability:** If there is a massive spike in transactions (like Black Friday), you can spin up 10 instances of the Fraud Service to process the Kafka queue faster, without touching the Transaction Service.
* **Fault Tolerance:** If the Fraud Service crashes, the Transaction Service stays online. Customers can still submit payments, and Kafka will simply hold the messages safely until the Fraud Service reboots.
* **Resume Value:** This proves you aren't just writing basic CRUD apps. It shows you understand **Distributed Systems**, **Asynchronous Messaging**, and **Event-Driven Architecture**—which are the exact skills Senior Engineers use at top tech companies.
