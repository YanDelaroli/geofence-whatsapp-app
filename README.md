# Geofence WhatsApp App

Aplicativo Android em Kotlin que permite cadastrar locais por CEP, endereço ou localização atual, definir um raio e configurar uma mensagem de WhatsApp para cada local. Ao detectar entrada em uma área ativa, o app mostra uma notificação que abre o WhatsApp com a mensagem pronta para envio.

## Recursos atuais

- Autocomplete de endereço enquanto o usuário digita
- Até 5 sugestões de endereço via Photon / OpenStreetMap
- Cadastro por CEP ou endereço
- Botão para usar a localização atual do celular
- Conversão interna do endereço para coordenadas
- Ajuste do raio entre 50 m e 1 km
- Vários locais salvos
- Nome, endereço, número e mensagem independentes por local
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
- Conexão com a internet para as sugestões de endereço

Não é necessária chave do Google Maps.

## Permissões

O app solicita localização precisa. Para geofencing continuar funcionando em segundo plano no Android 10+, também é necessário conceder a permissão de localização "Sempre" nas configurações do app. Em Android 13+, autorize notificações.

## Cadastro do local

Há duas formas:

1. Comece a digitar um CEP ou endereço. Após pelo menos 3 caracteres, o app mostra sugestões. Toque no resultado correto para selecionar o local.
2. Toque em **Usar minha localização atual** para cadastrar o ponto onde o celular está.

Também existe o botão **Buscar endereço completo** como alternativa caso nenhuma sugestão seja adequada.

Depois informe o nome do local, raio, número do WhatsApp e mensagem e toque em **Salvar e ativar este local**.

## Sugestões de endereço

As sugestões usam o Photon, um geocodificador baseado em dados do OpenStreetMap e projetado para pesquisa conforme o usuário digita. O app aplica um pequeno atraso antes da consulta e limita os resultados a 5 itens.

Os dados de endereço são atribuídos a Photon / OpenStreetMap na interface.

## Limite de áreas

O Android permite até 100 geofences ativas por aplicativo para cada usuário do dispositivo. O app bloqueia o cadastro acima desse limite.
