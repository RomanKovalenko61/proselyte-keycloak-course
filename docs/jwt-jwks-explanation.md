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

## Про поле "iss" (issuer) в JWT — что это и частые проблемы

- Что такое `iss`:
  - `iss` (issuer) — стандартный claim в JWT, указывающий поставщика токена (issuer). Для Keycloak обычно это URL realm'а, например `http://keycloak:8080/realms/proselyte` или `http://localhost:9090/realms/proselyte`.

- Почему `iss` важен:
  - Ресурсный сервер (например `eventapp`) проверяет не только подпись токена, но и значение `iss`. Spring Security (при использовании `issuer-uri`) требует, чтобы claim `iss` точно совпадал с настроенным `issuer-uri`.

- Частые причины несоответствий `iss` / `issuer-uri` в Docker-среде:
  1. Использование `localhost` внутри контейнера. Внутри контейнера `localhost` — это сам контейнер, а не хост или другой сервис. Поэтому Keycloak может выдавать `iss: http://keycloak:8080/...`, а в `application.yml` у сервиса стояло `issuer-uri: http://localhost:9090/...` — возникает mismatch.
  2. Разные значения `KC_HOSTNAME`, `KEYCLOAK_FRONTEND_URL` и проброс портов. Keycloak генерирует ссылки и `iss` в зависимости от конфигурации хоста/фронтенда.
  3. Редиректы в браузере ведут на имя, которое не резолвится на хосте (например `keycloak`), поэтому flow ломается и cookie не приходят — визуально это выглядит как ошибки логина, но корень часто в несогласованности хостов.

- Как быстро проверить значение `iss` в токене и совпадение:

1) Декодировать payload JWT и посмотреть поле `iss`:

```bash
echo '<JWT>' | cut -d '.' -f2 | base64 --decode | jq .
```

2) Проверить, что `issuer-uri` в `application.yml` совпадает с `iss` в токене.

3) Проверить OIDC discovery (в окружении приложения):

```bash
# из контейнера eventapp (если Keycloak в Docker)
docker compose exec eventapp curl -sS http://keycloak:8080/realms/proselyte/.well-known/openid-configuration | jq .issuer

# с хоста (если вы заходите по localhost)
curl -sS http://localhost:9090/realms/proselyte/.well-known/openid-configuration | jq .issuer
```

- Как исправить несоответствие (варианты):

1) Соглашение имён (рекомендуемый для dev):
   - В `docker-compose.yml` у Keycloak выставить `KC_HOSTNAME: keycloak` и `KC_HOSTNAME_STRICT: 'false'`, а в `eventapp` прописать `issuer-uri: http://keycloak:8080/realms/proselyte` — тогда внутренние сервисы будут работать по имени сервиса.
   - Для доступа через браузер на хосте оставить `KEYCLOAK_FRONTEND_URL: http://localhost:9090` или добавить запись в `/etc/hosts` `127.0.0.1 keycloak` — в зависимости от того, какое поведение вы хотите.

2) Использовать `jwk-set-uri` + ручную проверку issuer (когда discovery недоступен):
   - Создать `NimbusJwtDecoder` с `withJwkSetUri(...)` и явно навесить валидатор issuer через `decoder.setJwtValidator(...)`.
   - Это полезно, когда discovery endpoint недоступен из приложения, но JWKs доступны по другому адресу.

3) Поддержка нескольких issuer (если нужно быстро):
   - Реализовать бин `JwtDecoder`, который пробует несколько issuer/jwk-set-uri (например `http://keycloak:8080` и `http://localhost:9090`) и принимает первый успешный. Это удобно временно, но не рекомендуется для продакшна.

- Безопасность:
  - Не отключайте проверку issuer в проде. Если вы временно используете `jwk-set-uri` без валидатора issuer для удобства, обязательно позже вернуть строгую проверку `iss`.

- Быстрые команды для диагностики при проблеме:

```bash
# проверить, какой issuer отдает discovery (из того окружения, где работает приложение)
# внутри контейнера eventapp:
docker compose exec eventapp curl -sS http://keycloak:8080/realms/proselyte/.well-known/openid-configuration | jq .issuer

# с хоста (браузерная сторона):
curl -sS http://localhost:9090/realms/proselyte/.well-known/openid-configuration | jq .issuer

# декодирование токена (повторно):
echo '<JWT>' | cut -d '.' -f2 | base64 --decode | jq .
```
