# Geofence WhatsApp App

Aplicativo Android em Kotlin que registra uma área geográfica (geofence) e, ao detectar entrada nessa área, exibe uma notificação que abre o WhatsApp com uma mensagem predefinida para um número escolhido.

## MVP

- Cadastro de latitude, longitude e raio
- Cadastro de número do WhatsApp e mensagem
- Registro de geofence com Google Play services Location
- Detecção de entrada em segundo plano via `BroadcastReceiver`
- Notificação que abre `https://wa.me/<numero>?text=<mensagem>`

> O WhatsApp não é acionado silenciosamente. O usuário toca na notificação e confirma o envio no WhatsApp.

## Requisitos

- Android Studio recente
- JDK 17
- Android SDK 36
- Dispositivo Android com Google Play services

## Permissões

O app solicita localização precisa. Para geofencing continuar funcionando em segundo plano no Android 10+, também é necessário conceder a permissão de localização "Sempre" nas configurações do app. Em Android 13+, autorize notificações.
