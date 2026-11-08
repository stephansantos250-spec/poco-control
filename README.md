# POCO CONTROL — versão 1.9 experimental

Aplicativo Android para consultar configurações, tentar alterar opções de desenvolvedor e depuração com Shizuku e orientar o diagnóstico de acesso a aplicativos bancários. Não é afiliado à CAIXA, Xiaomi ou ao Gov.br e não garante acesso aos aplicativos bancários.

## Downloads
A [versão 1.9 experimental](https://github.com/stephansantos250-spec/poco-control/releases/tag/v1.9.0-experimental) contém o APK e o projeto Android completo em ZIP.

## Instalação e segurança
O APK usa uma assinatura de teste diferente da 1.8. Atualizar diretamente a partir dela não é possível: desinstalar a versão anterior apaga dados locais, inclusive valores salvos para restauração e atalhos. Antes de desinstalar, anote ou restaure os valores necessários. Não é necessário desinstalar o Shizuku.

O APK foi compilado, mas não foi testado no aparelho do usuário. O modo banco tenta desativar três configurações e o modo normal tenta restaurar apenas os valores salvos dessas três configurações. Desativar a depuração pode interromper o Shizuku. Os modos não desativam serviços de acessibilidade; eles devem ser gerenciados nas configurações do Android. O diagnóstico pode ser incompleto e não verifica todos os critérios de segurança de bancos.

## Compilação
Abra o projeto no Android Studio com JDK 17 e Android SDK 35. Sincronize as dependências e compile com `assembleRelease`. A configuração release do projeto utiliza uma chave de depuração local; um APK compilado em outro computador não necessariamente poderá atualizar o APK disponibilizado aqui. Não use chave de depuração em produção.# POCO CONTROL — versão 1.9 experimental

Aplicativo Android para consultar configurações, tentar alterar opções de desenvolvedor e depuração com Shizuku e orientar o diagnóstico de acesso a aplicativos bancários. Não é afiliado à CAIXA, Xiaomi ou ao Gov.br e não garante acesso aos aplicativos bancários.

## Downloads
A [versão 1.9 experimental](https://github.com/stephansantos250-spec/poco-control/releases/tag/v1.9-experimental) contém o APK e o projeto Android completo em ZIP.

## Instalação e segurança
O APK usa uma assinatura de teste diferente da 1.8. Atualizar diretamente a partir dela não é possível: desinstalar a versão anterior apaga dados locais, inclusive valores salvos para restauração e atalhos. Antes de desinstalar, anote ou restaure os valores necessários. Não é necessário desinstalar o Shizuku.

O APK foi compilado, mas não foi testado no aparelho do usuário. O modo banco tenta desativar três configurações e o modo normal tenta restaurar apenas os valores salvos dessas três configurações. Desativar a depuração pode interromper o Shizuku. Os modos não desativam serviços de acessibilidade; eles devem ser gerenciados nas configurações do Android. O diagnóstico pode ser incompleto e não verifica todos os critérios de segurança de bancos.

## Compilação
Abra o projeto no Android Studio com JDK 17 e Android SDK 35. Sincronize as dependências e compile com `assembleRelease`. A configuração release do projeto utiliza uma chave de depuração local; um APK compilado em outro computador não necessariamente poderá atualizar o APK disponibilizado aqui. Não use chave de depuração em produção.
