# Объяснение: почему помогло добавление jwk-set-uri

Ниже — краткая понятная подсказка (на русском), почему в вашем случае приложение заработало после добавления
`jwk-set-uri`, что происходило ранее (ошибки `Connection refused` / `issuer` mismatch), и безопасные рекомендации.

---

## Кратко

- `issuer-uri` — Spring Boot обращается к `{issuer}/.well-known/openid-configuration`, берёт оттуда `jwks_uri` и автоматически добавляет строгую проверку совпадения claim `iss` в токене с указанным `issuer`.
- `jwk-set-uri` — Spring Boot строит `JwtDecoder` напрямую по URL JWKs и по умолчанию не добавляет автоматическую проверку `iss` (если вы не подставите вручную `JwtValidator`).

Ваша ситуация: внутри Docker-контейнера `eventapp` адреса `localhost:...` и сервисные имена (например `keycloak:8080`) — разные вещи. Запросы на `localhost` из контейнера идут в сам контейнер, а не на Keycloak. Поэтому при использовании `issuer-uri: http://localhost:9090/...` Spring пытался достать `.well-known` по `localhost` внутри контейнера и получал `Connection refused`. При другой комбинации вы получали `Issuer ... did not match ...` потому что в токене поле `iss` содержало `http://keycloak:8080/...`, а в конфиге был `http://localhost:8080/...` — и Spring строго сравнивал их.

Добавление `jwk-set-uri: http://keycloak:8080/realms/proselyte/protocol/openid-connect/certs` решило две проблемы:
1. JWKs стали доступны по URL, который реально доступен из `eventapp` (внутренняя сеть Docker: `keycloak:8080`).
2. При создании decoder'а по `jwk-set-uri` автоматическая строгая проверка `iss` не срабатывала, поэтому несовпадение текста `iss` уже не блокировало валидацию — подпись проверялась по ключам и запрос проходил.

> Важно: это работает, но потенциально снижает безопасность (если вы не добавите вручную валидатор issuer). Поэтому рекомендуется либо привести issuer в соответствие, либо навесить проверку issuer вручную.

---

## Быстрые проверки (из вашего проекта)

1) Проверить `iss` в токене (на хосте):

```bash
# подставьте ваш JWT вместо <JWT>
echo '<JWT>' | cut -d '.' -f2 | base64 --decode | jq .
```

2) Проверить доступность JWKS из контейнера `eventapp`:

```bash
cd /home/astek/IdeaProjects/proselyte-keycloak-course
# выполнится внутри контейнера eventapp
docker compose exec eventapp curl -v http://keycloak:8080/realms/proselyte/protocol/openid-connect/certs
```

3) Проверить OIDC metadata (если используете issuer-uri):

```bash
docker compose exec eventapp curl -v http://keycloak:8080/realms/proselyte/.well-known/openid-configuration
```

Если в конфиге стоял `issuer-uri: http://localhost:9090/...`, то внутри контейнера `curl http://localhost:9090/...` упадёт с `Connection refused` — потому что внутри контейнера `localhost` не указывает на Keycloak-сервис.

---

## Рекомендации (более безопасные)

1. Лучшее решение — добиться, чтобы `iss` в токене совпадал с `issuer-uri` в `application.yml`:
   - В `docker-compose.yml` у сервиса `keycloak` используйте `KC_HOSTNAME: keycloak` (имя сервиса), или удалите `KC_HOSTNAME`, чтобы Keycloak генерировал корректные URL доступные внутри сети Docker.
   - Тогда в `eventapp` в `application.yml` укажите:
     ```yaml
     spring.security.oauth2.resourceserver.jwt.issuer-uri: http://keycloak:8080/realms/proselyte
     ```
   - Это даст максимально строгую проверку и корректную автоматическую конфигурацию.

2. Если менять Keycloak нельзя — используйте `jwk-set-uri` + ручный валидатор issuer:
   - Создайте `NimbusJwtDecoder` с `withJwkSetUri(...)` и навесьте валидатор issuer через `decoder.setJwtValidator(...)`.
   - Пример (Java):

```java
String jwkSetUri = "http://keycloak:8080/realms/proselyte/protocol/openid-connect/certs";
String issuer = "http://keycloak:8080/realms/proselyte";

NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
// Опционально: аудитория
OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator("eventapp");
OAuth2TokenValidator<Jwt> delegating = new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator);

decoder.setJwtValidator(delegating);
```

Где `AudienceValidator` — простой класс, реализующий `OAuth2TokenValidator<Jwt>` и проверяющий содержится ли ожидаемая аудитория в `jwt.getAudience()`.

3. Убедитесь, что в `application.yml` используете адреса, доступные из контейнера (`keycloak:8080`), а не `localhost` (если Keycloak в другом контейнере).

---

## Пояснение различий Mac vs Linux (почему у автора могло работать «из коробки»)

- Docker Desktop на Mac/Windows иногда обеспечивает специальную маршрутизацию `localhost` между хостом и контейнерами, либо автор запускал Keycloak локально на хосте. На Linux `localhost` внутри контейнера указывает на сам контейнер, поэтому запросы к `localhost:9090` не попадут в Keycloak-контейнер. Потому на Linux чаще требуется использовать сервисные имена Docker-сети (`keycloak`) или `host.docker.internal` при соответствующей настройке.

---

## Заключение и краткие советы

- Добавление `jwk-set-uri` решило проблему сети и валидации подписи, но не гарантирует проверку issuer — для безопасности навесьте валидатор issuer.
- Лучший путь — привести issuer в токене и `issuer-uri` в конфиге к одному значению (использовать `keycloak:8080` внутри compose-сети).
- Для отладки используйте команды `curl` внутри `eventapp` контейнера и декодирование JWT локально.

Если хотите, могу: 1) подготовить готовый Java-файл `JwtDecoderConfig` и вставить его в проект; 2) показать точную правку `docker-compose.yml` для `KC_HOSTNAME`; или 3) открыть этот markdown в редакторе — скажите, что предпочитаете.
