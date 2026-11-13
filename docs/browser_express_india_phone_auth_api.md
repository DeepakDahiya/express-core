# Phone OTP Auth API

Last updated: 2026-07-10

Source commit checked: `3161327` (`login wiht phone number`)

Base path:

```text
https://api.browser.express/v1/auth
```

All requests use:

```http
Content-Type: application/json
```

Phone auth currently supports Indian E.164 numbers only, for example
`+919876543210`. The request field can be `phone` or `phoneNumber`; prefer
`phone` in new clients. OTPs expire after `expiresInSeconds` returned by the
API, default `300` seconds.

## Unified Phone Auth Flow

1. Call `POST /register` to start phone auth. If the phone belongs to a verified
   user, the backend sends a login OTP. Otherwise it creates or updates a
   pending signup and sends a signup OTP.
2. Call `POST /verify-phone` with the OTP. A successful response returns auth
   tokens.
3. Store the returned `purpose` and call `POST /resend-phone-otp` with that
   same `purpose` if the user asks for a new code.

## Start Phone Auth

```http
POST /v1/auth/register
```

This is the same endpoint as existing email signup. Sending `phone`,
`phoneNumber`, or `country: "IN"` selects the unified phone OTP path.

Request:

```json
{
  "phone": "+919876543210",
  "country": "IN",
  "name": "Asha Sharma"
}
```

`phone` is required. `country` is optional and defaults to `IN` for this phone
flow. `name` is required only when the phone number does not already belong to a
verified user.

Success response:

```json
{
  "ok": true,
  "otpSent": true,
  "phone": "+919876543210",
  "purpose": "signup",
  "existingUser": false,
  "expiresInSeconds": 300
}
```

If the phone belongs to a verified user, `purpose` is `login` and
`existingUser` is `true`.

Common errors:

| Status | `error` | Meaning |
| --- | --- | --- |
| `400` | `invalid_phone` | Phone is missing or not an Indian E.164 number. |
| `400` | `invalid_country` | `country` is present and not `IN`. |
| `400` | `invalid_name` | `name` is missing or longer than 160 chars for a new signup. |
| `403` | `account_disabled` | Existing account is deleted/disabled. |
| `429` | `rate_limited` | Too many OTP requests by IP or phone. |
| `429` | `otp_rate_limited` | OTP resend cooldown is active; response includes `retryAfterSeconds`. |
| `503` | `otp_delivery_failed` | SMS could not be sent. Retry later. |

## Legacy Phone Login

New clients should use `POST /register` for both login and signup. The
`POST /phone-login/start` endpoint remains for older clients that only know the
separate login flow.

## Start Phone Login

```http
POST /v1/auth/phone-login/start
```

Request:

```json
{
  "phone": "+919876543210"
}
```

Success response:

```json
{
  "ok": true,
  "otpSent": true,
  "phone": "+919876543210",
  "purpose": "login",
  "existingUser": true,
  "expiresInSeconds": 300
}
```

Common errors:

| Status | `error` | Meaning |
| --- | --- | --- |
| `400` | `invalid_phone` | Phone is missing or not an Indian E.164 number. |
| `403` | `account_disabled` | Account is deleted/disabled. |
| `404` | `phone_not_registered` | No verified account exists for this phone number. |
| `429` | `rate_limited` | Too many OTP requests by IP or phone. |
| `429` | `otp_rate_limited` | OTP resend cooldown is active; response includes `retryAfterSeconds`. |
| `503` | `otp_delivery_failed` | SMS could not be sent. Retry later. |

## Resend OTP

```http
POST /v1/auth/resend-phone-otp
```

Request:

```json
{
  "phone": "+919876543210",
  "purpose": "signup"
}
```

`purpose` must be either `signup` or `login`.

Success response:

```json
{
  "ok": true,
  "otpSent": true,
  "purpose": "signup",
  "expiresInSeconds": 300
}
```

Common errors:

| Status | `error` | Meaning |
| --- | --- | --- |
| `400` | `invalid_phone` | Phone is missing or not an Indian E.164 number. |
| `400` | `invalid_purpose` | `purpose` is not `signup` or `login`. |
| `404` | `pending_signup_not_found` | No pending signup exists for this phone. |
| `404` | `phone_not_registered` | No verified account exists for this phone. |
| `429` | `rate_limited` | Too many OTP requests by IP or phone. |
| `429` | `otp_rate_limited` | OTP resend cooldown is active; response includes `retryAfterSeconds`. |
| `503` | `otp_delivery_failed` | SMS could not be sent. Retry later. |

## Verify OTP

Use this endpoint for both signup verification and phone login verification.

```http
POST /v1/auth/verify-phone
```

Request:

```json
{
  "phone": "+919876543210",
  "otp": "123456"
}
```

Success response:

```json
{
  "accessToken": "<jwt>",
  "refreshToken": "<jwt>",
  "user": {
    "id": "665f1f0c0000000000000000",
    "phone": "+919876543210",
    "firstName": "Asha",
    "lastName": "Sharma",
    "name": "Asha Sharma",
    "country": "IN"
  }
}
```

Common errors:

| Status | `error` | Meaning |
| --- | --- | --- |
| `400` | `invalid_phone` | Phone is missing or not an Indian E.164 number. |
| `400` | `invalid_otp` | OTP is missing or not numeric. |
| `401` | `invalid_otp` | OTP is incorrect or expired. |
| `403` | `account_disabled` | Account is deleted/disabled. |
| `404` | `phone_challenge_not_found` | No active OTP challenge exists for this phone. |
| `429` | `rate_limited` | Too many verification attempts by IP or phone. |
| `429` | `too_many_otp_attempts` | Too many incorrect OTP attempts. Request a new code. |
| `500` | `phone_verification_failed` | Verification failed unexpectedly. Retry later. |

## Related India Auth Behavior

For requests detected as India (`country: "IN"` or country headers such as
`cf-ipcountry: IN`), anonymous username generation is blocked:

```http
GET /v1/auth/generate-username?country=IN
POST /v1/auth/generate-username
```

Response:

```json
{
  "error": "manual_auth_required",
  "message": "Phone verification is required for this region."
}
```
