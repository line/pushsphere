# Pushsphere

_Pushsphere_ is an open-source push notification client library and gateway server for Kotlin.
It currently supports two push notification service providers, although it was designed with supporting
wide variety of providers in mind:

- [APNs (Apple Push Notification service)](https://developer.apple.com/documentation/usernotifications) for iOS and macOS
- [FCM (Firebase Cloud Messaging)](https://firebase.google.com/docs/cloud-messaging) for Android

It is composed of the following components:

## Pushsphere Client

Pushsphere Client is a client-side library that lets you send push notifications to the push notification
service providers. It has sophisticated built-in mechanisms to ensure reliable delivery, including:

- Client-side load balancing
- Quota-aware automatic retries
- Circuit breakers

## Pushsphere Server

Pushsphere Server is a gateway server that provides [the REST API](api-specification.yaml) to forward
the push notifications to the push notification service providers. It allows you to configure all push
notification settings in a centralized manner, e.g.,

- Keep all your credentials (API access tokens and key pairs) in one place.
- Use different credentials for different apps.
- Expose various low- and high-level metrics.

Note that Pushsphere Server is **completely optional** to send push notifications.
Pushsphere Client is capable of sending push notifications to APNs and FCM on its own, and indeed,
Pushsphere Server uses Pushsphere Client internally to send push notifications.

## How to build

```
$ ./gradlew build
```

## How to run with the example configuration

```
$ dist/example/local-run.sh
```

To run in a Docker container:

```
$ dist/example/docker-run.sh
```

## Contact

- Please feel free to create a new issue or a new discussion post.
