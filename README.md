# POCO CONTROL — 2.0 experimental

Aplicativo Android experimental, não afiliado à CAIXA, Xiaomi ou Gov.br. Não garante acesso a aplicativos bancários. As alterações de configurações dependem do Android e do Shizuku; o funcionamento não foi testado em um aparelho físico.

## Acessibilidade
Em Ferramentas, **Escolher serviços para desativar** lista serviços ativos identificados como pertencentes a Shizuku-Next, TogglDev/ToggleDev e Tooler. Selecione apenas os serviços desejados e confirme. O app tenta remover só os componentes escolhidos da lista de acessibilidade do Android por meio do Shizuku, preservando os outros componentes lidos. O Android pode restringir a operação ou o Shizuku pode parar, deixando o resultado incerto; confirme cada serviço nas configurações. Se um serviço não aparecer, use as configurações oficiais. A lista pode ser incompleta.

O modo banco não altera acessibilidade. O modo normal não reativa esses serviços: para reativar, use as configurações de acessibilidade do Android. Não desative serviços essenciais de acessibilidade sem uma alternativa para usar o aparelho.

## Modos e instalação
O modo banco tenta desativar três configurações de desenvolvedor e depuração, guardando os valores para que o modo normal tente restaurá-los. Outras configurações não são restauradas. Desativar depuração pode interromper o Shizuku. A assinatura do APK 2.0 experimental é de teste e pode ser diferente da anterior; desinstalar o app apaga atalhos e os valores salvos. Anote ou restaure valores importantes antes de desinstalar.

## Compilação
Abra o projeto no Android Studio com JDK 17 e Android SDK 35 e execute `assembleRelease`. A versão release usa uma chave de depuração local e não deve ser publicada em produção. O ZIP não contém chaves de assinatura.
