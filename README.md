# Round Robin Routing API

This project demonstrates a round-robin routing mechanism across multiple backend service instances with a circuit breaker mechanism using **Resilience4j** and **Spring WebFlux**.

## 🛠 Tech Stack

- Java 21
- Spring Boot 3
- WebClient
- Resilience4j CB
- Docker

---

## 🚀 Project Structure

```
round-robin-project/
├── application-api/      # Backend instance application. Currently configured on 3 ports
├── routing-api/          # Entry point routing service
├── docker-compose.yml    # Compose file to run everything
```

---

## 📦 Build Project

```bash
mvn clean package
```

---

## 🐳 Run with Docker Compose

### Start all applications (build and run):

```bash
docker compose up --build
```

### Start only application-api instances:

```bash
docker compose up application-api-1 application-api-2 application-api-3
```

---

## 📜 Logs

| Command                                                                       | Purpose                                    |
|-------------------------------------------------------------------------------| ------------------------------------------ |
| `docker compose logs application-api-1`                                       | Logs for specific instance (port 8081)     |
| `docker compose logs -f application-api-1 application-api-2 application-api-3` | Real-time streaming for multiple instances |
| `docker compose logs routing-api`                                             | Logs for routing API service               |

---

## 🔁 Start/Stop Instances Individually

```bash
docker compose stop application-api-2

docker compose start application-api-2
```

---

## 💻 Run Without Docker (Local Terminals)

### Start backend services in individual terminals:

```bash
java -jar application-api/target/application-api-0.0.1-SNAPSHOT.jar --server.port=8081
java -jar application-api/target/application-api-0.0.1-SNAPSHOT.jar --server.port=8082
java -jar application-api/target/application-api-0.0.1-SNAPSHOT.jar --server.port=8083
```

### Then run the Routing API:

```bash
java -jar routing-api/target/routing-api-0.0.1-SNAPSHOT.jar \
  --application.api.instances=http://localhost:8081,http://localhost:8082,http://localhost:8083
```

---

## 🧪 Testing Behavior

- Routing is performed in round-robin fashion.
- Preemptive health checkup on application startup to get healthy instances. [This can be enhanced to polling every few mins in future]
- If an instance fails, circuit breaker opens and skips the call. Moves to next available instance
- After a configured wait time, circuit breaker transitions to half-open and retries and subsequently close after success.

---


