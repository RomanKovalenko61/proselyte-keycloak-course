# Стадия сборки
FROM gradle:8.14-jdk24 AS builder

WORKDIR /app

# Копируем только файлы, влияющие на зависимости, чтобы кэшировать скачивание зависимостей
COPY build.gradle settings.gradle ./
COPY gradle.properties* ./

# Кэш слоёв зависимостей (важно!)
RUN gradle dependencies --no-daemon

# Копируем остальной код и собираем fat jar
COPY . ./
RUN gradle bootJar --no-daemon

# Финальный образ (runtime)
FROM eclipse-temurin:24-jre

WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8091

ENTRYPOINT ["java", "-jar", "app.jar"]
