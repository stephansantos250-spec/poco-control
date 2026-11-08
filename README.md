# POCO CONTROL — 2.2 experimental

Aplicativo Android experimental, não afiliado à CAIXA, Xiaomi ou Gov.br. Não garante acesso a aplicativos bancários. Não testado em aparelho físico.

## Download

[Baixe a versao 2.2 experimental](https://github.com/stephansantos250-spec/poco-control/releases/tag/v2.2.0-experimental).

## Poco Control e acessibilidade
O Poco Control mostra os serviços ativos identificados de Shizuku-Next, TogglDev/ToggleDev e Tooler. Após confirmação, tenta removê-los da lista real de serviços ativos por meio do Shizuku, preservando os demais serviços lidos, e em seguida tenta desativar desenvolvedor, depuração USB e sem fio. A identificação depende dos nomes informados pelo Android e pode ser incompleta; nomes ausentes não são considerados desativados. Confira os resultados nas configurações oficiais.

O Android pode restringir a alteração ou o Shizuku pode parar, deixando o resultado incerto ou parcial. O modo normal tenta restaurar somente os três valores de desenvolvedor/depuração salvos; ele não restaura serviços de acessibilidade. Reative-os manualmente nas configurações do Android, se precisar. A opção separada em Ferramentas permite escolher serviços manualmente. Nunca desative recursos essenciais sem alternativa de acessibilidade.

## Instalação e compilação
Assinado com chave de teste. Se a assinatura for diferente da instalação anterior, será preciso desinstalá-la; isso apaga atalhos e os valores salvos para restauração. Anote ou restaure os valores antes de desinstalar. O aplicativo foi compilado, mas não foi testado em aparelho físico.

Abra o projeto no Android Studio com JDK 17 e SDK 35. Sincronize dependências e execute `assembleRelease`. A configuração release usa chave de depuração local; não a use em produção. O ZIP não contém chaves de assinatura.
