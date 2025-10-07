# --- Build Stage ---
# This stage uses a full JDK image with Gradle to build the application JAR.
# It will not be part of the final image.
FROM gradle:8.5.0-jdk17-jammy AS builder

WORKDIR /app

# Copy Gradle wrapper, build scripts, and settings
COPY gradlew .
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# Download dependencies as a separate layer to leverage Docker cache
RUN ./gradlew dependencies --no-daemon

# Copy the rest of the source code
COPY src ./src

# Build the application, skipping tests for a faster build
RUN ./gradlew bootJar --no-daemon -x test

# --- Final Stage ---
# This stage uses a lightweight JRE image and copies only the built JAR.
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]