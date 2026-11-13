# POCO CONTROL — 3.8

Aplicativo Android experimental, não afiliado à CAIXA, Xiaomi ou Gov.br. Não garante acesso a aplicativos bancários.

## Download

A [versão antiga 2.3](https://github.com/stephansantos250-spec/poco-control/releases/tag/v2.3.0-experimental) permanece disponível. Confira as releases para o APK e o código da versão 3.8.

## Aparência
Toque na engrenagem no topo → **Temas** e selecione **Liquid Glass** ou **Material 4**. A escolha fica salva no aparelho. O Liquid Glass usa camadas translúcidas, tons de azul e reflexos suaves, sem exigir desfoque do sistema. Os botões principais são azuis nos dois temas. Material 4 é apenas o nome de um tema inspirado na linguagem Material, não uma versão oficial do Google. O painel foi reorganizado e a pesquisa de aplicativos evita refiltrar a lista sem necessidade; não foram realizados testes de desempenho.

## Aba Acessibilidade e notificações
Na aba **Acessibilidade**, o painel agrupa por aplicativo os serviços de acessibilidade que o Android informa como instalados neste perfil. Serviços não ativados aparecem primeiro; use **Escanear agora** para atualizar. O Android não informa se o app realmente precisa ser ativado e pode omitir serviços ou outros perfis. Cada serviço tem **Ativar** e **Desativar**; a ação sempre pede confirmação e tenta alterar apenas o serviço escolhido usando o Shizuku autorizado. Nunca ativa serviços recém-detectados sozinho. Serviços de acessibilidade podem ler a tela e controlar o aparelho: só ative aqueles em que confia. O valor na lista de configurações não prova que o serviço está funcionando; o Android pode bloquear a alteração ou pedir confirmação manual. Se necessário, use **Abrir acessibilidade do Android**. Uma alteração individual confirmada retira esse serviço do registro pendente do Modo normal, para respeitar a escolha mais recente.

Na primeira leitura bem-sucedida é criada uma lista inicial, sem avisos para os serviços já existentes. Depois, novos serviços não confirmados como ativados geram uma notificação para revisão, se as notificações estiverem permitidas. Avisos pendentes são tentados novamente após a permissão, desde que o serviço ainda exista e continue não confirmado como ativado. Não é possível saber se um app realmente precisa ser ativado. Toque em **Permitir notificações** (Android 13 ou superior) ou ajuste as notificações no Android. O painel atualiza ao abrir e ao receber mudanças de pacotes enquanto o processo estiver ativo; uma tarefa periódica também verifica em segundo plano. Essa verificação é inexacta (intervalo mínimo de 15 minutos), pode ser adiada por economia de bateria ou restrições do fabricante, e pode parar após encerramento forçado até o app ser aberto novamente. Não há garantia de aviso imediato nem de cobertura fora do perfil atual. Confira manualmente se necessário.

## Apps instalados e configurações restritas
A aba **Apps instalados** lista aplicativos disponíveis no perfil atual, com busca e filtros **Apps do usuário**, **Apps do sistema** e **Apps desativados**. A lista é consultada e exibida localmente; o aplicativo solicita `QUERY_ALL_PACKAGES` para conseguir exibir o inventário amplo. Perfis de trabalho, ocultação por políticas e limitações do fabricante podem impedir a exibição de determinados apps. A permissão tem restrições de uso na Google Play e deve ser revisada antes de qualquer publicação.

Ao tocar em um app, o painel mostra atalhos para as telas oficiais: informações do app, acessibilidade, acesso à leitura de notificações e notificações que o app envia. Os atalhos de acessibilidade e leitura de notificações abrem listas gerais do Android; selecione o serviço desejado. A consulta de compatibilidade indica apenas serviços declarados encontrados, não confirma permissões nem necessidade de uso. Opções e telas variam por dispositivo. Um app desativado pode não oferecer todas as opções até ser ativado nas configurações do Android.

Para permitir configurações restritas em um app confiável no Android 13 ou superior, abra as informações do app → menu ⋮ → **Permitir configurações restritas**, se aparecer, e siga as instruções do aparelho. Isso não concede automaticamente acesso à acessibilidade ou às notificações; revise cada permissão separadamente. O POCO CONTROL não verifica o estado dessa proteção nem a altera automaticamente. Fonte: https://support.google.com/android/answer/12623953?hl=pt-BR

## Restauração
Ao desativar serviços de acessibilidade identificados ou escolhidos, o aplicativo salva seus componentes antes da alteração. O modo normal tenta acrescentar esses serviços à lista atual sem remover os demais e restaura também os três valores de desenvolvedor, depuração USB e depuração sem fio previamente salvos. Os registros são mantidos quando não é possível confirmar a restauração. A ativação depende das permissões, da versão do Android, do fabricante e do Shizuku; uma entrada na lista não garante que o serviço esteja em funcionamento. Confira nas configurações do Android.

Os serviços desativados pela versão 2.3 **não foram registrados** e não podem ser restaurados automaticamente por esta versão: reative-os manualmente em Configurações → Acessibilidade. Se o Shizuku parou ao desativar a depuração, reinicie-o e autorize o POCO CONTROL antes de tentar restaurar. Se não for possível, reative manualmente as opções desejadas nas configurações oficiais. A ativação de depuração reduz a segurança; habilite-a apenas se precisar. O Android pode alterar outras opções ao desligar o modo desenvolvedor, e elas não são restauradas por este aplicativo.

O app só seleciona os serviços identificados de SD Maid (AppCleaner) - ADB/Root Toolbox, Shizuku-Next, TogglDev/ToggleDev e Tooler. A identificação depende dos nomes informados pelo Android e pode ser incompleta. Não desligue recursos essenciais de acessibilidade sem alternativa. Uma alteração concorrente na lista de acessibilidade pode interferir; confira o resultado e evite mudanças simultâneas.

## Instalação e compilação
Abra o projeto no Android Studio com JDK 17, Gradle 9.3.1 e SDK Platform 37.0. Sincronize as dependências e execute `assembleRelease`. A configuração release usa a chave de depuração local; não a use em produção. O ZIP não contém chaves de assinatura. Para atualizar sem perder dados, o APK deve ser assinado com a mesma chave da instalação anterior. Uma assinatura diferente exige desinstalação, que apaga atalhos e estados salvos: **anote ou restaure os valores antes de desinstalar**. Não use este projeto para substituir a chave original.

## Roteiro de teste no aparelho
0. Teste a aba Acessibilidade com serviços não essenciais, Shizuku ativo e inativo; permita e negue notificações; instale um app de teste com serviço de acessibilidade, aguarde a verificação e toque no aviso. Confira também interrupções e restrições de bateria. Abra Configurações → Temas. Experimente ambos, feche e reabra o aplicativo para conferir a persistência. Verifique legibilidade, tamanho de fonte ampliado e navegação por leitor de tela.
1. Inicie e autorize o Shizuku. Ative opções de desenvolvedor e serviços não essenciais de teste; anote os estados originais e mantenha uma forma de controlar o aparelho.
2. Desative pelo aplicativo. Confira que só os serviços identificados foram afetados, e que outros continuam na lista.
3. Se o Shizuku parar, reinicie-o e autorize-o. Use Modo normal → Restaurar valores; confira os três valores e serviços no Android. Pode ser necessário ativá-los manualmente, especialmente na primeira restauração depois da versão 2.3.
4. Teste seleção individual, repetição da desativação, serviço já reativado manualmente, uma alteração feita na lista entre as operações, e uma interrupção do Shizuku. Nenhum resultado incerto deve ser tratado como sucesso; uma nova tentativa deve manter o registro.
5. Teste em aparelhos e versões Android reais antes de distribuir. Não há garantia de ativação automática em todos os aparelhos.

## Conexão Wi-Fi local (3.2)
A conexão ADB local é separada da autorização do Shizuku. Em Android 11 ou superior, ative as opções de desenvolvedor e a depuração sem fio; inicie a busca na notificação e abra a tela de pareamento no Android. O app tenta descobrir a porta automaticamente por mDNS; após ser encontrado, informe apenas o código na notificação. Depois de parear, tente conectar automaticamente ou informe a porta de conexão manualmente, que pode ser diferente e mudar. Informe apenas um IPv4 do próprio aparelho. O app não oferece terminal arbitrário. A chave privada gerada pelo cliente é criptografada usando uma chave AES no Android Keystore; o código de pareamento não é salvo. O app não cria uma porta de depuração: o Android fornece a porta. Após reiniciar ou trocar de rede pode ser necessário reconectar. Fechar a conexão no app não revoga o pareamento no Android; remova o dispositivo pareado nas configurações de depuração sem fio.

O modo Wi-Fi mantém desenvolvedor e depuração sem fio ativos, tenta desligar USB e os serviços escolhidos, e salva o estado anterior quando possível. A restauração pode interromper a conexão. A depuração expõe o aparelho a riscos e pode ser detectada por apps bancários. A operação pode ser parcial; confira o resultado no Android. Recursos ADB e pareamento devem ser validados em aparelho real antes de confiar neles.

Bibliotecas de terceiros: Kadb 2.1.3 (Apache-2.0) e spake2-java 1.1.1 (GPL-3.0) e Conscrypt (Apache-2.0); consulte os arquivos em licenses/ e os respectivos projetos de origem: https://github.com/flyfishxu/Kadb e https://github.com/Flyfish233/spake2-java.

A opção Criar notificação de pareamento descobre o serviço local de pareamento e permite responder com apenas o código sem sair da tela do Android. A descoberta requer Wi-Fi, notificações e execução em segundo plano; no MIUI/POCO talvez seja necessário usar o estilo de notificação Android e liberar a execução em segundo plano. Se não descobrir o serviço, a opção manual continua disponível. A descoberta é encerrada após 2 minutos; o pareamento após 30 segundos pode ficar incerto. A resposta não é gravada pelo aplicativo. Após o pareamento, tente Conectar automaticamente ou informe a porta de conexão manualmente. Confira todas as falhas e atrasos no Android.

A tela principal oferece três modos: Poco Control tenta desligar desenvolvedor, depuração USB e sem fio e os serviços identificados; pode interromper a conexão e deixar o trabalho incompleto. Modo Wi-Fi mantém desenvolvedor e depuração sem fio ativos e tenta desligar USB e serviços identificados. Modo normal tenta restaurar os valores e serviços salvos. Wi-Fi ADB não funciona quando a depuração sem fio ou desenvolvedor estão desligados; para restaurar pode ser necessário reativá-los manualmente e reconectar.

A interface de descoberta segue a estratégia de serviço mDNS e notificação do projeto Shizuku (Apache-2.0): https://github.com/RikkaApps/Shizuku. Todos os novos recursos exigem testes em aparelhos reais; o projeto não garante compatibilidade com todos os fabricantes.

## Fluxo de pareamento inspirado no vídeo
Abra Configurar conexão Wi-Fi local e toque em Iniciar pareamento. Permita notificações, se solicitado, e toque novamente no botão se necessário. Abra Opções do desenvolvedor → Depuração sem fio → Parear dispositivo com código. Mantenha a tela aberta e responda à notificação de serviço encontrado com os seis dígitos. Após a confirmação, toque em Começar na notificação: o POCO CONTROL abre e tenta descobrir e iniciar a conexão local. Também é possível usar Iniciar conexão na tela ou, se a descoberta falhar, expandir Pareamento e conexão manuais. Pareamento e conexão são etapas separadas; isso não inicia nem autoriza automaticamente o serviço Shizuku.

O fluxo foi inspirado nas etapas demonstradas no vídeo https://www.youtube.com/watch?v=67v0JEx20bo e usa o cliente ADB próprio do POCO CONTROL, não uma cópia do serviço Shizuku. O app não consegue funcionar via Wi-Fi se a depuração sem fio estiver desativada. O modo Poco Control pode interromper essa conexão; o modo Wi-Fi procura preservá-la. O pareamento, a notificação, a busca mDNS e a conexão precisam de testes em um dispositivo físico.


## Aparência 3.3

Os dois temas foram redesenhados com navegação azul, superfícies mais definidas, cartões e uma pequena prévia no seletor. Liquid Glass usa reflexos e gradientes leves (sem desfoque real em tempo real); Material 4 é um estilo visual inspirado em Material, não uma versão oficial. As funções de acesso e pareamento permanecem as mesmas da versão 3.2.

## Pareamento e conexão 3.4

O pareamento tenta primeiro o loopback do próprio aparelho, como no fluxo do Shizuku. Só tenta o endereço local descoberto se o loopback recusar a conexão TCP, antes da autenticação. A notificação usa resposta direta do Android e um ícone monocromático de escudo; a aparência exata é controlada pelo sistema, incluindo One UI e MIUI. O botão de busca identifica o serviço anunciado na rede; o app não lê nem gera o código da tela do Android.

A busca da porta de conexão tenta a rede Wi-Fi e, em seguida, a descoberta padrão do Android, com novas tentativas para falhas de resolução. A porta de conexão é diferente da porta de pareamento. Em caso de falha, categorias P01–P99 e C01–C02 não incluem código nem endereço. O pareamento, a aparência das notificações e a descoberta precisam de teste em aparelho real; compatibilidade com versões e fabricantes não é garantida.


## Versão 3.5: investigação do P99

Esta é a versão principal com todas as funções da 3.4 preservadas. O diagnóstico da notificação de pareamento, da opção manual e da conexão inclui somente os nomes das classes da primeira exceção e da causa final; não inclui as mensagens, código de pareamento, IP ou porta. Falhas de biblioteca na opção manual são tratadas, sem afirmar sucesso. A causa do P99 ainda é desconhecida e o pareamento não foi confirmado em aparelho real. É necessário testar e informar Tipo e Causa (sem compartilhar o código, IP ou porta).

A assinatura da build de teste 3.5 é diferente da v3.4 enviada anteriormente: não atualiza a instalação v3.4. Desinstalar apaga todos os dados e registros de restauração do aplicativo. Revise e restaure os estados com a versão antiga antes de qualquer desinstalação; use a chave original da 3.4 se estiver disponível.


## Versão 3.6: compatibilidade da chave RSA

A biblioteca Kadb foi atualizada de 2.1.1 para 2.1.3. Essa versão inclui a correção publicada pelo projeto para extrair a chave pública de chaves RSA não expostas como RSAPrivateCrtKey (https://github.com/flyfishxu/Kadb/commit/1bc7ad76d0bfe63936943d7f7edea5bdef4f90e0). Essa é uma causa provável do PolicyViolation observado, não uma confirmação sem teste no aparelho. Permanece o armazenamento da chave ADB privada criptografada e as funções existentes. O diagnóstico não revela o código, IP, porta nem a mensagem da exceção.

Dependências alteradas: Kadb 2.1.3 (Apache-2.0) e spake2-java 1.1.1 (GPL-3.0); respectivos fontes e licenças em third_party e assets/licenses.

Compilação da 3.6: Gradle 9.3.1, Android Gradle Plugin 9.1.1, SDK Platform 37.0 e Build Tools 36.0.0, com JDK 17 para o Gradle. O teste de compatibilidade da Kadb precisa ser executado em JVM 21 ou posterior, pois a biblioteca possui classes Java 21; a build final usa o D8 do Android. Os 11 testes locais passaram, incluindo o teste de chave RSA sem interface CRT. Não houve teste de pareamento em aparelho físico. A assinatura deste APK 3.6 é a mesma da build 3.5 entregue, permitindo atualização sobre a 3.5; isso não se aplica à antiga 3.4 com outra chave.

## Versão 3.7: ordem de desativação

O modo Poco Control solicita a desativação na ordem USB, sem fio e desenvolvedor, por um script fixo. Ele verifica a depuração sem fio após uma breve espera, tenta novamente uma vez se necessário e interrompe antes da etapa final se não conseguir confirmá-la. Após a tentativa, o app lê os estados localmente e relata qualquer resultado incompleto; não afirma que alterações externas futuras são impossíveis. A leitura é pontual, e o Android/HyperOS ou outros apps podem alterar os valores depois. A conexão ADB sem fio pode cair ao desativá-la antes da etapa final; prefira o Shizuku autorizado e conclua manualmente se necessário. O modo normal ainda restaura os valores salvos, inclusive a depuração sem fio se antes estava ativa, somente quando solicitado pelo usuário. O modo Wi-Fi exige a depuração sem fio ativa. A compatibilidade e a permanência dos valores precisam de teste em dispositivo físico.

A build 3.7 aqui entregue usa uma assinatura de teste diferente da build 3.6. Não pode ser instalada como atualização sobre a 3.6. A desinstalação apaga os dados locais do POCO CONTROL, inclusive registro de restauração, atalhos e chave de pareamento. Antes de desinstalar, restaure ou anote as configurações e os serviços necessários; depois será necessário parear novamente. Para atualizar preservando dados, assine a 3.7 com a mesma chave privada usada na 3.6. Não houve teste em aparelho físico.


## Versão 3.8: aparência e carregamento

Liquid Glass recebeu superfícies azul-escuras, reflexos e camadas leves; Material 4 usa superfícies escuras mais sólidas. O vidro é uma representação com gradientes, sem desfoque real em tempo real. O cabeçalho usa o ícone do app; navegação, visão geral e cartões foram refinados. Material 4 é apenas o nome do tema visual, não uma versão oficial do Material.

As varreduras do inventário e da acessibilidade usam uma fila separada das operações privilegiadas, com solicitações repetidas agrupadas; ícones de aplicativos são mantidos em cache limitado e limpos quando há mudança de pacotes. Não houve benchmark nem teste em dispositivo físico. Os modos, o pareamento e a ordem da versão 3.7 foram preservados.

A build 3.8 entregue foi assinada com uma chave de teste diferente da 3.7 (e da 3.6), portanto não pode ser instalada por cima delas. Desinstalar a versão anterior apaga os registros locais de restauração, atalhos e a chave de pareamento. Antes de desinstalar, restaure ou anote as configurações e serviços necessários; depois será necessário parear novamente. Para atualizar preservando dados, é preciso assinar esta versão com a mesma chave privada da versão instalada. Os 15 testes locais passaram; não houve teste nem medição de desempenho em aparelho físico.
