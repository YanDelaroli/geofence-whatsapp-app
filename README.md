# Geofence WhatsApp App

Aplicativo Android em Kotlin que permite escolher locais em um mapa, definir um raio e configurar uma mensagem de WhatsApp para cada local. Ao detectar entrada em uma área ativa, o app mostra uma notificação que abre o WhatsApp com a mensagem pronta para envio.

## Recursos atuais

- Seleção do local tocando no Google Maps
- Ajuste visual do raio entre 50 m e 1 km
- Vários locais salvos
- Nome, número e mensagem independentes por local
- Ativar/desativar regras individualmente
- Excluir regras salvas
- Registro simultâneo de geofences com Google Play services Location
- Detecção de entrada em segundo plano via `BroadcastReceiver`
- Notificação que abre `https://wa.me/<numero>?text=<mensagem>`

> O WhatsApp não envia a mensagem silenciosamente. O usuário toca na notificação e confirma o envio no WhatsApp.

## Requisitos

- Android Studio recente
- JDK 17
- Android SDK 36
- Dispositivo Android com Google Play services
- Projeto no Google Cloud com Maps SDK for Android ativada
- Chave de API do Google Maps

## Configurar o mapa

1. No Google Cloud Console, crie ou escolha um projeto.
2. Ative a **Maps SDK for Android**.
3. Crie uma chave de API e, de preferência, restrinja-a ao app Android.
4. Na raiz do projeto, crie um arquivo chamado `secrets.properties`.
5. Adicione:

```properties
MAPS_API_KEY=SUA_CHAVE_AQUI
```

O arquivo `secrets.properties` está no `.gitignore` e não deve ser enviado ao GitHub.

## Permissões

O app solicita localização precisa. Para geofencing continuar funcionando em segundo plano no Android 10+, também é necessário conceder a permissão de localização "Sempre" nas configurações do app. Em Android 13+, autorize notificações.

## Limite de áreas

O Android permite até 100 geofences ativas por aplicativo para cada usuário do dispositivo. O app bloqueia o cadastro acima desse limite.
