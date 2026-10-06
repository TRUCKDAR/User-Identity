# TruckDar — User-Identity Microservice

Microservicio de **identidad, autenticación y autorización** de la plataforma
TruckDar. Gestiona el ciclo de vida de usuarios (conductores, coordinadores,
administradores), autenticación JWT (access + refresh tokens) y publica
eventos de dominio al bus de eventos compartido.

---

## Stack

| Tecnología | Versión |
|---|---|
| Java | 21 |
| Spring Boot | 3.3.5 |
| PostgreSQL | 16 |
| Flyway | Migraciones versionadas |
| JWT | jjwt 0.12.6 |
| MapStruct | 1.5.5.Final |
| Docker | Multi-stage build |

---

## Arquitectura del Microservicio

### 1. Diagrama de Contexto (Ecosistema TruckDar)

```mermaid
flowchart TB
    subgraph Clientes["Clientes de la Plataforma"]
        direction LR
        MobileApp["📱 App Móvil (Flutter / RN)<br/>Conductores de Carga"]
        WebPanel["💻 Torre de Control (React Web)<br/>Coordinadores & Despachadores"]
    end

    subgraph Perimetro["Perímetro de Red / Entrada"]
        ApiGateway["🛡️ API Gateway (Kong / AWS API Gateway)<br/>• Enrutamiento • Rate Limiting • SSL Termination"]
    end

    subgraph UserIdentityBoundary["Microservicio User-Identity (Puerto 8080)"]
        direction TB
        Security["🔐 Spring Security 6<br/>(Stateless JWT Filter)"]
        RestControllers["🎮 Capa REST (Controllers)<br/>• AuthController<br/>• UserController"]
        BusinessServices["⚙️ Capa de Negocio (Services)<br/>• AuthServiceImpl<br/>• UserServiceImpl<br/>• JwtProvider"]
        DataRepositories["💾 Capa de Persistencia<br/>• UserRepository<br/>• RefreshTokenRepository"]
    end

    subgraph Datos["Almacenamiento de Datos"]
        PostgresDB[("🐘 PostgreSQL 16<br/>truckdar_identity<br/>(Users + Refresh Tokens)")]
    end

    subgraph Eventos["Bus de Eventos Asíncrono"]
        KafkaBus[["📨 Kafka / AWS MSK<br/>Event Bus Compartido"]]
        FleetsService["🚛 Microservicio Flotas"]
        DispatchService["📋 Microservicio Asignación"]
    end

    MobileApp -->|HTTPS /api/v1/auth/*| ApiGateway
    WebPanel -->|HTTPS /api/v1/users/*| ApiGateway
    ApiGateway -->|Ruta interna HTTP| Security
    Security --> RestControllers
    RestControllers --> BusinessServices
    BusinessServices --> DataRepositories
    DataRepositories -->|JPA / Flyway SQL| PostgresDB
    BusinessServices -.->|Publica eventos de dominio<br/>(UserRegistered, UserRoleChanged)| KafkaBus
    KafkaBus -.->|Consume eventos| FleetsService
    KafkaBus -.->|Consume eventos| DispatchService

    classDef client fill:#e0f2fe,stroke:#0284c7,stroke-width:2px,color:#0369a1;
    classDef gateway fill:#fef3c7,stroke:#d97706,stroke-width:2px,color:#92400e;
    classDef core fill:#ecfdf5,stroke:#059669,stroke-width:2px,color:#065f46;
    classDef data fill:#f3e8ff,stroke:#9333ea,stroke-width:2px,color:#6b21a8;
    classDef events fill:#ffe4e6,stroke:#e11d48,stroke-width:2px,color:#9f1239;

    class MobileApp,WebPanel client;
    class ApiGateway gateway;
    class Security,RestControllers,BusinessServices,DataRepositories core;
    class PostgresDB data;
    class KafkaBus,FleetsService,DispatchService events;
```

### 2. Arquitectura Interna por Capas

```mermaid
graph TD
    ClientReq["Petición HTTP entrante"] --> JwtFilter["JwtAuthenticationFilter<br/>(Extrae Bearer Token & valida firma)"]
    JwtFilter --> SecurityContext["SecurityContextHolder<br/>(ROLE_CONDUCTOR / ROLE_COORDINADOR / ROLE_ADMIN)"]
    SecurityContext --> DispatcherServlet["Spring MVC DispatcherServlet"]

    subgraph Presentation["Capa de Presentación / Web"]
        DispatcherServlet --> AuthCtrl["AuthController"]
        DispatcherServlet --> UserCtrl["UserController"]
        GlobalEx["GlobalExceptionHandler<br/>(@RestControllerAdvice)"] -.->|Intercepta excepciones| ClientReq
    end

    subgraph Application["Capa de Aplicación y Servicios"]
        AuthCtrl --> AuthSvc["AuthService / AuthServiceImpl"]
        UserCtrl --> UserSvc["UserService / UserServiceImpl"]
        AuthSvc -.-> JwtProv["JwtProvider<br/>(Generación & validación HMAC-SHA512)"]
        AuthSvc -.-> PassEnc["PasswordEncoder<br/>(BCrypt hashing)"]
        AuthSvc & UserSvc -.-> Mappers["MapStruct Mappers<br/>(UserMapper)"]
        AuthSvc & UserSvc -.-> EventPub["DomainEventPublisher<br/>(Logging / Kafka)"]
    end

    subgraph Persistence["Capa de Dominio y Persistencia"]
        AuthSvc & UserSvc --> UserRepo["UserRepository (Spring Data JPA)"]
        AuthSvc --> TokenRepo["RefreshTokenRepository"]
        UserRepo & TokenRepo --> DB[("PostgreSQL 16 / H2")]
    end
```

### 3. Flujo de Autenticación y Autorización (JWT)

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario (App / Web)
    participant SEC as Spring Security / Filtro
    participant CTRL as AuthController
    participant SVC as AuthServiceImpl
    participant BC as PasswordEncoder (BCrypt)
    participant JWT as JwtProvider
    participant DB as PostgreSQL
    participant BUS as Event Publisher

    %% Registro
    rect rgb(240, 248, 255)
    note over U,BUS: 1. Flujo de Registro
    U->>CTRL: POST /api/v1/auth/register (datos + rol)
    CTRL->>SVC: register(request)
    SVC->>DB: findByEmail(email)
    alt Email ya registrado
        SVC-->>U: 409 Conflict (UserAlreadyExistsException)
    else Email disponible
        SVC->>BC: encode(rawPassword)
        BC-->>SVC: passwordHash
        SVC->>DB: save(User)
        SVC->>JWT: generateAccessToken(User) + generateRefreshToken()
        JWT-->>SVC: Tokens
        SVC->>DB: save(RefreshToken)
        SVC->>BUS: publish(UserRegisteredEvent)
        SVC-->>U: 201 Created: { accessToken, refreshToken, tokenType, expiresIn }
    end
    end

    %% Petición Protegida
    rect rgb(240, 255, 240)
    note over U,BUS: 2. Petición a Endpoint Protegido (ej. GET /api/v1/users/me)
    U->>SEC: GET /api/v1/users/me + Header "Authorization: Bearer <token>"
    SEC->>JWT: validateToken(token)
    alt Token válido
        JWT-->>SEC: Claims (userId, role=CONDUCTOR)
        SEC->>SEC: Establece SecurityContext (ROLE_CONDUCTOR)
        SEC->>CTRL: Pasa la petición al UserController
        CTRL->>U: 200 OK con UserResponse
    else Token inválido o expirado
        SEC-->>U: 401 Unauthorized (JwtAuthenticationEntryPoint)
    end
    end
```

### 4. Modelo Entidad-Relación (Base de Datos)

```mermaid
erDiagram
    USERS {
        uuid id PK "UUID autogenerado"
        varchar email UK "Email único"
        varchar password_hash "Hash BCrypt"
        varchar first_name "Nombre"
        varchar last_name "Apellido"
        varchar phone_number "Teléfono de contacto"
        varchar document_type "CC, CE, PASAPORTE, NIT"
        varchar document_number "Número de documento"
        varchar role "CONDUCTOR | COORDINADOR | ADMIN"
        varchar status "PENDING_VERIFICATION | ACTIVE | SUSPENDED"
        timestamptz created_at "Fecha de creación"
        timestamptz updated_at "Fecha de última actualización"
        timestamptz last_login_at "Último inicio de sesión"
    }

    REFRESH_TOKENS {
        uuid id PK "UUID autogenerado"
        uuid user_id FK "Referencia a USERS(id) ON DELETE CASCADE"
        varchar token UK "Cadena única del token"
        timestamptz expires_at "Fecha de expiración (7 días)"
        boolean revoked "Estado de revocación (logout/rotación)"
    }

    USERS ||--o{ REFRESH_TOKENS : "1 usuario tiene 0..N"
```

---

## Requisitos previos

- **Java 21** (JDK)
- **Maven 3.9+** (o usar el wrapper `./mvnw`)
- **Docker** y **Docker Compose**

---

## Levantar localmente

### 1. Iniciar PostgreSQL con Docker Compose

```bash
docker compose up -d postgres
```

Esto levanta PostgreSQL en `localhost:5432` con:
- DB: `truckdar_identity`
- User: `truckdar`
- Password: `truckdar`

> **Tip:** También puedes levantar pgAdmin en `localhost:5050` con
> `docker compose up -d pgadmin`

### 2. Compilar y ejecutar el servicio

```bash
# Compilar (sin tests)
./mvnw clean package -DskipTests

# Opción A: Probar rápidamente en memoria (sin requerir Docker ni PostgreSQL)
./mvnw spring-boot:run "-Dspring-boot.run.profiles=standalone"

# Opción B: Ejecutar con PostgreSQL local (requiere Docker o Postgres en localhost:5432)
./mvnw spring-boot:run "-Dspring-boot.run.profiles=local"
```

El servicio arranca en `http://localhost:8080`.
*(En el modo `standalone`, la consola de base de datos H2 queda disponible en `http://localhost:8080/h2-console` con JDBC URL `jdbc:h2:mem:truckdar_identity` y usuario `sa`)*.

### 3. Acceder a Swagger UI

```
http://localhost:8080/swagger-ui.html
```

---

## Ejecutar tests

### Tests unitarios

```bash
./mvnw test
```

### Tests de integración (requiere Docker para Testcontainers)

```bash
./mvnw verify
```

> Los tests de integración usan **Testcontainers** para levantar una
> instancia de PostgreSQL real automáticamente — no necesitas tener
> Postgres corriendo manualmente.

---

## Build con Docker

```bash
# Construir la imagen
docker build -t truckdar/user-identity:latest .

# Ejecutar el contenedor
docker run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/truckdar_identity \
  -e DB_USERNAME=truckdar \
  -e DB_PASSWORD=truckdar \
  -e JWT_SECRET=dHJ1Y2tkYXItdXNlci1pZGVudGl0eS1zZWNyZXQta2V5LWJhc2U2NC1lbmNvZGVkLWF0LWxlYXN0LTI1Ni1iaXRz \
  -e SPRING_PROFILES_ACTIVE=local \
  truckdar/user-identity:latest
```

---

## Endpoints principales

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/api/v1/auth/register` | Registro de usuario |
| `POST` | `/api/v1/auth/login` | Login (access + refresh token) |
| `POST` | `/api/v1/auth/refresh` | Renovar access token |
| `POST` | `/api/v1/auth/logout` | Revocar refresh token |
| `GET` | `/api/v1/users/me` | Perfil del usuario autenticado |
| `PUT` | `/api/v1/users/me` | Actualizar perfil propio |
| `GET` | `/api/v1/users/{id}` | Consultar usuario (ADMIN/COORDINADOR) |
| `GET` | `/api/v1/users` | Listado paginado (ADMIN) |
| `PATCH` | `/api/v1/users/{id}/status` | Suspender/reactivar (ADMIN) |
| `PATCH` | `/api/v1/users/{id}/role` | Cambiar rol (ADMIN) |

---

## Variables de entorno

| Variable | Descripción | Default |
|---|---|---|
| `DB_URL` | JDBC URL de PostgreSQL | `jdbc:postgresql://localhost:5432/truckdar_identity` |
| `DB_USERNAME` | Usuario de la BD | `truckdar` |
| `DB_PASSWORD` | Contraseña de la BD | `truckdar` |
| `JWT_SECRET` | Secret para firmar JWTs (Base64) | *(dev default)* |
| `JWT_ACCESS_EXPIRATION` | TTL del access token (ms) | `900000` (15 min) |
| `JWT_REFRESH_EXPIRATION` | TTL del refresh token (ms) | `604800000` (7 días) |
| `SERVER_PORT` | Puerto del servidor | `8080` |

---

## Estructura del proyecto

```
src/main/java/escuelaing/edu/co/truckdar/User_Identity/
├── controller/          # REST controllers
├── service/             # Interfaces de lógica de negocio
│   └── impl/            # Implementaciones
├── repository/          # Spring Data JPA repositories
├── model/               # Entidades JPA + enums
├── dto/
│   ├── request/         # DTOs de entrada
│   └── response/        # DTOs de salida
├── mapper/              # MapStruct (entity <-> DTO)
├── security/            # JWT filter, SecurityConfig
├── exception/           # Excepciones + GlobalExceptionHandler
├── config/              # OpenAPI, beans de configuración
├── event/               # Eventos de dominio + publisher
└── UserIdentityApplication.java
```

---

## Perfiles de Spring

| Perfil | Uso |
|---|---|
| `standalone` | Prueba rápida en memoria (H2, sin requerir Docker/Postgres) |
| `local` | Desarrollo local (SQL logging, PostgreSQL) |
| `dev` | Ambiente compartido de desarrollo |
| `prod` | Producción (ECS Fargate) |
| `test` | Tests de integración |