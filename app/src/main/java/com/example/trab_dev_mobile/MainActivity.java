package com.example.trab_dev_mobile;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteException;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.net.http.HttpResponseCache;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.example.trab_dev_mobile.BancoRastreios.Ponto;
import com.example.trab_dev_mobile.BancoRastreios.Registro;

public class MainActivity extends Activity implements LocationListener, SensorEventListener {
    private static final int PEDIR_LOCALIZACAO = 1;
    private static final String GPS = LocationManager.GPS_PROVIDER;
    private static final String REDE = LocationManager.NETWORK_PROVIDER;
    private static final long IDADE_GPS_MS = 10_000;
    private static final long IDADE_REDE_MS = 120_000;
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    // Mantém os blocos do mapa em um cache separado da configuração anterior.
    private static final XYTileSource MAPA_OSM = new XYTileSource(
            "RastreadorGpsOSM", 0, 19, 256, ".png",
            new String[]{"https://tile.openstreetmap.org/"},
            "© OpenStreetMap contributors",
            new TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_BULK
                    | TileSourcePolicy.FLAG_NO_PREVENTIVE
                    | TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL));

    private BancoRastreios banco;
    private final ExecutorService filaBanco = Executors.newSingleThreadExecutor();
    private LocationManager localizacao;
    private SensorManager sensores;
    private VistaTrajeto mapa;
    private TextView aviso, avisoSinal, velocidade, elevacao, movimento;
    private Button botao;
    private TableLayout tabela;
    private AlertDialog dialogoHistorico;

    // Guarda os pontos em memória até o usuário tocar em Parar rastreio.
    private final List<Ponto> trajeto = new ArrayList<>();
    private Location posicaoGps, posicaoRede, ultimoPonto;
    private boolean gravando, salvando, telaVisivel, ouvindoLocalizacao, iniciarAposPermissao;
    private long inicioMs, trechoDesdeMs = -1, duracaoAcumuladaMs;
    private double distanciaM;
    private final float[] gravidade = new float[3];
    private boolean calibrado;
    private long ultimaLeituraSensorMs;

    private final Runnable atualizarRelogio = new Runnable() {
        /**
         * Atualiza o texto da velocidade média.
         * Agenda outra atualização em um segundo enquanto o trecho estiver ativo.
         */
        @Override
        public void run() {
            mostrarVelocidade();
            if (gravando && trechoDesdeMs >= 0) {
                velocidade.postDelayed(this, 1_000);
            }
        }
    };

    /**
     * Prepara o banco, os serviços do Android e a tela inicial.
     * Carrega o histórico e solicita a localização na primeira abertura, se necessário.
     */
    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        banco = new BancoRastreios(this);
        localizacao = getSystemService(LocationManager.class);
        sensores = getSystemService(SensorManager.class);

        configurarMapa();
        montarTela();
        atualizarHistorico();

        if (estado == null && !temPermissaoGps()) {
            pedirPermissaoGps();
        }
    }

    /**
     * Configura o OpenStreetMap antes de criar as visualizações do mapa.
     * Define a identificação do app, o limite de downloads e os caches de imagens.
     */
    private void configurarMapa() {
        File pastaMapa = new File(getCacheDir(), "osmdroid");
        File blocosMapa = new File(pastaMapa, "tiles");
        blocosMapa.mkdirs();
        Configuration.getInstance().setOsmdroidBasePath(pastaMapa);
        Configuration.getInstance().setOsmdroidTileCache(blocosMapa);
        Configuration.getInstance().load(this, getSharedPreferences("mapa", MODE_PRIVATE));
        // Identifica o aplicativo nas requisições ao OpenStreetMap.
        Configuration.getInstance().setUserAgentValue("RastreadorGPS-Academico/1.0 (" + getPackageName() + ")");
        Configuration.getInstance().setTileDownloadThreads((short) 2);

        // O cache HTTP reaproveita os cabeçalhos de validação enviados pelo servidor.
        if (HttpResponseCache.getInstalled() == null) {
            try {
                HttpResponseCache.install(new File(getCacheDir(), "mapa-http"), 32L * 1024 * 1024);
            } catch (IOException erro) {
                Log.w("RastreadorGPS", "Falha ao abrir o cache HTTP do mapa", erro);
            }
        }
    }

    /**
     * Carrega o layout XML e associa seus componentes aos campos usados no rastreio.
     * Encaixa o mapa customizado na área reservada e liga o botão às ações de gravação.
     */
    private void montarTela() {
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT >= 30) {
            ScrollView rolagem = findViewById(R.id.main);
            rolagem.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bordas = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bordas.left, bordas.top, bordas.right, bordas.bottom);
                return insets;
            });
            rolagem.requestApplyInsets();
        }

        FrameLayout areaMapa = findViewById(R.id.area_mapa);
        mapa = new VistaTrajeto();
        mapa.setVisibility(View.INVISIBLE);
        // A primeira camada fica atrás do aviso de localização definido no XML.
        areaMapa.addView(mapa, 0, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        aviso = findViewById(R.id.aviso_localizacao);
        avisoSinal = findViewById(R.id.aviso_sinal);
        configurarCreditoMapa(findViewById(R.id.credito_mapa));

        botao = findViewById(R.id.botao_rastreio);
        botao.setOnClickListener(v -> {
            if (gravando) {
                pararRastreio();
            } else {
                iniciarRastreio();
            }
        });

        velocidade = findViewById(R.id.velocidade);
        elevacao = findViewById(R.id.elevacao);
        movimento = findViewById(R.id.movimento);
        tabela = findViewById(R.id.tabela_historico);
        mostrarVelocidade();
        atualizarAviso();
    }

    /**
     * Começa a receber posições quando a tela fica visível.
     * Retoma a contagem de um rastreio em andamento ao voltar para a tela.
     */
    @Override
    protected void onStart() {
        super.onStart();
        telaVisivel = true;
        ouvirLocalizacao();
        if (gravando && !salvando) {
            retomarContagem();
        }
    }

    /**
     * Reativa o mapa e registra a escuta do acelerômetro quando a tela volta ao primeiro plano.
     * A próxima leitura do sensor reinicia a estimativa de gravidade.
     */
    @Override
    protected void onResume() {
        super.onResume();
        mapa.onResume();
        Sensor acelerometro = sensores.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        calibrado = false;
        if (acelerometro == null
                || !sensores.registerListener(this, acelerometro, SensorManager.SENSOR_DELAY_UI)) {
            movimento.setText("Acelerômetro indisponível");
        }
    }

    /**
     * Pausa o mapa e deixa de ouvir o acelerômetro quando a tela perde o foco.
     */
    @Override
    protected void onPause() {
        sensores.unregisterListener(this);
        mapa.onPause();
        super.onPause();
    }

    /**
     * Interrompe as atualizações de localização quando a tela fica oculta.
     * Pausa a contagem e guarda o tempo já medido para uma eventual retomada.
     */
    @Override
    protected void onStop() {
        // Ao sair da tela, interrompe a leitura da localização e pausa o tempo do rastreio.
        telaVisivel = false;
        localizacao.removeUpdates(this);
        ouvindoLocalizacao = false;
        pausarContagem();
        super.onStop();
    }

    /**
     * Fecha a janela do histórico, se estiver aberta.
     * Agenda o fechamento do banco depois das operações já enfileiradas.
     */
    @Override
    protected void onDestroy() {
        if (dialogoHistorico != null) {
            dialogoHistorico.dismiss();
        }
        filaBanco.execute(banco::close);
        filaBanco.shutdown();
        super.onDestroy();
    }

    /**
     * Verifica se o Android concedeu a permissão de localização precisa.
     */
    private boolean temPermissaoGps() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Abre o diálogo do Android solicitando localização precisa e aproximada.
     * O código da solicitação permite identificar a resposta no callback de permissões.
     */
    private void pedirPermissaoGps() {
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, PEDIR_LOCALIZACAO);
    }

    /**
     * Recebe a resposta do Android ao pedido de localização.
     * Se o pedido foi aberto pelo botão de rastreio, inicia a gravação após a concessão.
     */
    @Override
    public void onRequestPermissionsResult(int codigo, String[] permissoes, int[] resultados) {
        super.onRequestPermissionsResult(codigo, permissoes, resultados);
        if (codigo == PEDIR_LOCALIZACAO) {
            ouvirLocalizacao();
            if (iniciarAposPermissao && temPermissaoGps()) {
                iniciarRastreio();
            }
            iniciarAposPermissao = false;
        }
    }

    /**
     * Registra os provedores disponíveis enquanto a tela estiver visível e autorizada.
     * O GPS fornece os pontos do trajeto. A rede ajuda a posicionar o mapa inicialmente.
     */
    @SuppressLint("MissingPermission")
    private void ouvirLocalizacao() {
        if (!telaVisivel || !temPermissaoGps() || ouvindoLocalizacao) {
            atualizarAviso();
            return;
        }

        List<String> provedores = localizacao.getAllProviders();
        posicaoGps = provedores.contains(GPS) ? ouvirProvedor(GPS, 1_000, IDADE_GPS_MS) : null;
        // A posição por Wi-Fi ou rede móvel serve para mostrar o mapa enquanto o GPS busca sinal.
        posicaoRede = provedores.contains(REDE) ? ouvirProvedor(REDE, 10_000, IDADE_REDE_MS) : null;
        ouvindoLocalizacao = true;
        mostrarPosicao();
    }

    /**
     * Solicita atualizações de um provedor e busca uma posição inicial no cache do Android.
     *
     * @param provedor fonte da localização, como GPS ou rede
     * @param intervaloMs intervalo solicitado entre atualizações, em milissegundos
     * @param idadeMaximaMs idade máxima aceita para a posição inicial, em milissegundos
     * @return posição inicial recente do provedor ativo, ou null enquanto aguarda uma posição
     */
    @SuppressLint("MissingPermission")
    private Location ouvirProvedor(String provedor, long intervaloMs, long idadeMaximaMs) {
        localizacao.requestLocationUpdates(provedor, intervaloMs, 0f, this, Looper.getMainLooper());
        // Reaproveita a última posição se ela for recente e o provedor estiver ativo.
        Location ultima = localizacao.getLastKnownLocation(provedor);
        return ultima != null && idadeMs(ultima) <= idadeMaximaMs
                && localizacao.isProviderEnabled(provedor) ? ultima : null;
    }

    /**
     * Verifica a permissão e a disponibilidade do GPS antes de começar.
     * Zera a distância e a duração, guarda o horário de início e mantém a tela ligada.
     */
    private void iniciarRastreio() {
        if (!temPermissaoGps()) {
            iniciarAposPermissao = true;
            pedirPermissaoGps();
            return;
        }
        if (!localizacao.isProviderEnabled(GPS)) {
            new AlertDialog.Builder(this)
                    .setMessage("Ative a localização para iniciar o rastreio.")
                    .setPositiveButton("Configurações", (d, i) ->
                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("Fechar", null)
                    .show();
            return;
        }

        trajeto.clear();
        distanciaM = 0;
        duracaoAcumuladaMs = 0;
        inicioMs = System.currentTimeMillis();
        gravando = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        botao.setText("Parar rastreio");
        mapa.invalidate();
        if (telaVisivel) {
            retomarContagem();
        }
    }

    /**
     * Congela a duração e prepara uma cópia do trajeto para gravar no SQLite.
     * Um trajeto vazio encerra sem registro. Uma falha de gravação permite tentar novamente.
     */
    private void pararRastreio() {
        pausarContagem();
        if (trajeto.isEmpty()) {
            Toast.makeText(this, "Nenhum ponto de GPS foi registrado.", Toast.LENGTH_SHORT).show();
            finalizarRastreio();
            return;
        }

        salvando = true;
        botao.setEnabled(false);
        botao.setText("Salvando…");
        long inicio = inicioMs;
        double distancia = distanciaM;
        long duracao = duracaoAcumuladaMs;
        // Envia ao banco uma cópia dos pontos coletados até a parada.
        List<Ponto> pontos = new ArrayList<>(trajeto);
        consultarBanco(() -> banco.salvar(inicio, distancia, duracao, pontos),
                id -> finalizarRastreio(), () -> {
                    // Mantém o trajeto em memória para permitir outra tentativa.
                    salvando = false;
                    botao.setEnabled(true);
                    botao.setText("Parar rastreio");
                    if (telaVisivel) {
                        retomarContagem();
                    }
                    Toast.makeText(this, "Falha ao salvar. Toque em Parar rastreio para tentar novamente.",
                            Toast.LENGTH_LONG).show();
                });
    }

    /**
     * Limpa a linha do trajeto e prepara o botão para uma nova gravação.
     * Atualiza a velocidade na tela e recarrega o histórico.
     */
    private void finalizarRastreio() {
        gravando = false;
        salvando = false;
        trajeto.clear();
        mapa.invalidate();
        botao.setText("Iniciar rastreio");
        botao.setEnabled(true);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mostrarVelocidade();
        atualizarHistorico();
    }

    /**
     * Marca o início de um trecho ativo e reinicia a atualização periódica da velocidade.
     */
    private void retomarContagem() {
        trechoDesdeMs = SystemClock.elapsedRealtime();
        velocidade.removeCallbacks(atualizarRelogio);
        atualizarRelogio.run();
    }

    /**
     * Acumula o tempo do trecho atual e interrompe as atualizações periódicas.
     * Limpa a referência do último ponto para separar os trechos ao retomar.
     */
    private void pausarContagem() {
        duracaoAcumuladaMs = duracaoMs();
        trechoDesdeMs = -1;
        ultimoPonto = null;
        velocidade.removeCallbacks(atualizarRelogio);
    }

    /**
     * Soma o tempo acumulado ao tempo do trecho em andamento, em milissegundos.
     * Usa o relógio monotônico para medir a duração mesmo quando o horário do celular muda.
     */
    private long duracaoMs() {
        return duracaoAcumuladaMs + (trechoDesdeMs < 0
                ? 0 : SystemClock.elapsedRealtime() - trechoDesdeMs);
    }

    /**
     * Calcula há quantos milissegundos o Android mediu a posição.
     * A idade ajuda a descartar posições antigas.
     */
    private static long idadeMs(Location ponto) {
        return SystemClock.elapsedRealtime() - ponto.getElapsedRealtimeNanos() / 1_000_000;
    }

    /**
     * Recebe uma posição do GPS ou da rede e atualiza o mapa.
     * Durante o rastreio, registra os pontos de GPS válidos e soma a distância entre eles.
     */
    @Override
    public void onLocationChanged(Location ponto) {
        if (!ouvindoLocalizacao) {
            return;
        }
        if (REDE.equals(ponto.getProvider())) {
            if (idadeMs(ponto) <= IDADE_REDE_MS) {
                posicaoRede = ponto;
                if (posicaoGps == null) {
                    mostrarPosicao();
                }
            }
            return;
        }
        if (!GPS.equals(ponto.getProvider()) || idadeMs(ponto) > IDADE_GPS_MS) {
            return;
        }
        // Grava pontos do trecho atual com margem de erro estimada de até 35 metros.
        if (gravando && trechoDesdeMs >= 0
                && ponto.getElapsedRealtimeNanos() / 1_000_000 >= trechoDesdeMs
                && ponto.hasAccuracy() && ponto.getAccuracy() <= 35) {
            // Descarta deslocamentos menores que a imprecisão do GPS.
            boolean novoTrecho = ultimoPonto == null;
            float deslocamento = novoTrecho ? 0 : ultimoPonto.distanceTo(ponto);
            if (novoTrecho || deslocamento >= Math.max(5f, ponto.getAccuracy())) {
                distanciaM += deslocamento;
                trajeto.add(new Ponto(ponto.getLatitude(), ponto.getLongitude(),
                        ponto.hasAltitude() ? ponto.getAltitude() : null, novoTrecho));
                ultimoPonto = ponto;
                mostrarVelocidade();
            }
        }
        posicaoGps = ponto;
        mostrarPosicao();
    }

    /**
     * Centraliza o mapa na posição do GPS ou, enquanto ela estiver indisponível, na posição da rede.
     * Usa um zoom mais aberto para a posição aproximada e exibe a elevação fornecida pelo GPS.
     */
    private void mostrarPosicao() {
        if (posicaoRede != null && idadeMs(posicaoRede) > IDADE_REDE_MS) {
            posicaoRede = null;
        }
        Location ponto = posicaoGps != null ? posicaoGps : posicaoRede;
        if (ponto != null) {
            mapa.pontos = trajeto;
            mapa.posicao = new GeoPoint(ponto.getLatitude(), ponto.getLongitude());
            mapa.getController().setZoom(posicaoGps != null ? 17.0 : 14.0);
            mapa.getController().setCenter(mapa.posicao);
            mapa.invalidate();
        }
        elevacao.setText(posicaoGps != null && posicaoGps.hasAltitude()
                ? String.format(PT_BR, "Elevação GPS: %.0f m", posicaoGps.getAltitude())
                : "Elevação GPS: —");
        atualizarAviso();
    }

    /**
     * Escolhe entre exibir o mapa e mostrar uma mensagem sobre permissão ou sinal.
     * Indica quando a posição desenhada vem da rede e é aproximada.
     */
    private void atualizarAviso() {
        boolean temPermissao = temPermissaoGps();
        boolean gpsAtivo = temPermissao && localizacao.isProviderEnabled(GPS);
        if (!temPermissao) {
            aviso.setText("Permita a localização precisa");
        } else if (!gpsAtivo) {
            aviso.setText("GPS desativado ou indisponível");
        } else {
            aviso.setText("Aguardando localização\nEm local fechado, tente ao ar livre.");
        }
        boolean mostrarMapa = temPermissao && (posicaoGps != null || posicaoRede != null);
        mapa.setVisibility(mostrarMapa ? View.VISIBLE : View.INVISIBLE);
        aviso.setVisibility(mostrarMapa ? View.GONE : View.VISIBLE);
        boolean aproximada = mostrarMapa && posicaoGps == null;
        avisoSinal.setVisibility(aproximada ? View.VISIBLE : View.GONE);
        if (aproximada) {
            avisoSinal.setText(gpsAtivo ? "Posição aproximada · aguardando GPS"
                    : "Posição aproximada · GPS indisponível");
        }
    }

    /**
     * Atualiza o aviso da tela quando o Android informa que um provedor foi ativado.
     */
    @Override
    public void onProviderEnabled(String provedor) {
        atualizarAviso();
    }

    /**
     * Descarta a posição do provedor desativado e atualiza o mapa.
     * Quando o GPS é desativado, o próximo ponto aceito inicia um novo trecho.
     */
    @Override
    public void onProviderDisabled(String provedor) {
        if (GPS.equals(provedor)) {
            ultimoPonto = null;
            posicaoGps = null;
        } else if (REDE.equals(provedor)) {
            posicaoRede = null;
        }
        mostrarPosicao();
    }

    /**
     * Mantém a compatibilidade com versões antigas da interface de localização.
     * O corpo vazio deixa o tratamento dos provedores a cargo dos callbacks de ativação e desativação.
     */
    @Override
    @SuppressWarnings("deprecation") // Método exigido pelas versões antigas do Android.
    public void onStatusChanged(String provedor, int status, Bundle extras) { }

    /**
     * Calcula a intensidade do movimento combinando os três eixos do acelerômetro.
     * Exibe o resultado em metros por segundo ao quadrado, até cinco vezes por segundo.
     */
    @Override
    public void onSensorChanged(SensorEvent evento) {
        // Estima e subtrai a gravidade para mostrar a intensidade do movimento.
        float soma = 0;
        for (int i = 0; i < 3; i++) {
            float leitura = evento.values[i];
            gravidade[i] = calibrado ? 0.8f * gravidade[i] + 0.2f * leitura : leitura;
            float linear = leitura - gravidade[i];
            soma += linear * linear;
        }
        calibrado = true;
        long agora = SystemClock.elapsedRealtime();
        if (agora - ultimaLeituraSensorMs >= 200) {
            ultimaLeituraSensorMs = agora;
            movimento.setText(String.format(PT_BR, "Movimento (acelerômetro): %.1f m/s²", Math.sqrt(soma)));
        }
    }

    /**
     * Recebe mudanças na precisão informada pelo sensor.
     * O corpo vazio mantém o indicador baseado nas leituras do acelerômetro.
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int precisao) { }

    /**
     * Atualiza o indicador com a velocidade média calculada a partir da distância e do tempo ativo.
     */
    private void mostrarVelocidade() {
        velocidade.setText("Velocidade média: " + formatarVelocidade(distanciaM, duracaoMs()));
    }

    /**
     * Converte a distância em metros e a duração em milissegundos em uma média em km/h.
     * Formata o resultado com uma casa decimal no padrão brasileiro.
     */
    private String formatarVelocidade(double distancia, long duracao) {
        return String.format(PT_BR, "%.1f km/h", BancoRastreios.velocidadeMediaKmh(distancia, duracao));
    }

    /**
     * Transforma o instante em milissegundos em uma data e hora no formato brasileiro.
     * A mesma apresentação é usada na tabela e no título do mapa salvo.
     */
    private String formatarData(long instante) {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", PT_BR).format(new Date(instante));
    }

    /**
     * Consulta os resumos no SQLite e reconstrói a tabela.
     * Os pontos de cada trajeto são carregados ao tocar na linha correspondente.
     */
    private void atualizarHistorico() {
        consultarBanco(banco::listar, registros -> {
            tabela.removeAllViews();
            adicionarLinha("Início do rastreio", "Velocidade média", null);
            if (registros.isEmpty()) {
                adicionarLinha("Nenhum rastreio salvo", "", null);
            }
            for (Registro registro : registros) {
                adicionarLinha(formatarData(registro.inicioMs),
                        formatarVelocidade(registro.distanciaM, registro.duracaoMs),
                        v -> abrirRegistro(registro));
            }
        }, () -> {
            tabela.removeAllViews();
            adicionarLinha("Falha ao ler o histórico. Toque para tentar novamente.", "",
                    v -> atualizarHistorico());
        });
    }

    /**
     * Adiciona uma linha com duas células à tabela do histórico.
     * Quando recebe uma ação, torna a linha clicável para abrir o registro ou repetir a consulta.
     */
    private void adicionarLinha(String data, String media, View.OnClickListener acao) {
        TableRow linha = new TableRow(this);
        TextView primeira = new TextView(this);
        primeira.setPadding(dp(4), dp(10), dp(8), dp(10));
        primeira.setText(data);
        TextView segunda = new TextView(this);
        segunda.setText(media);
        linha.addView(primeira);
        linha.addView(segunda);
        if (acao != null) {
            linha.setOnClickListener(acao);
            linha.setFocusable(true);
            android.util.TypedValue fundo = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, fundo, true);
            linha.setBackgroundResource(fundo.resourceId);
        }
        tabela.addView(linha);
    }

    /**
     * Carrega os pontos do rastreio selecionado pelo identificador salvo no banco.
     * Quando a consulta termina com sucesso, abre o mapa do registro.
     */
    private void abrirRegistro(Registro registro) {
        consultarBanco(() -> banco.carregarPontos(registro.id),
                pontos -> mostrarRegistro(registro, pontos),
                () -> Toast.makeText(this, "Falha ao carregar o trajeto.", Toast.LENGTH_SHORT).show());
    }

    /**
     * Abre uma janela com o mapa do trajeto salvo.
     * Centraliza um ponto único ou ajusta o zoom para enquadrar o percurso completo.
     */
    private void mostrarRegistro(Registro registro, List<Ponto> pontos) {
        if (!telaVisivel || dialogoHistorico != null) {
            return;
        }
        VistaTrajeto mapaSalvo = new VistaTrajeto();
        mapaSalvo.pontos = pontos;
        List<GeoPoint> coordenadas = new ArrayList<>();
        for (Ponto ponto : pontos) {
            coordenadas.add(new GeoPoint(ponto.latitude, ponto.longitude));
        }
        // Espera o mapa ter tamanho para ajustar o zoom ao trajeto inteiro.
        mapaSalvo.addOnFirstLayoutListener((v, l, t, r, b) -> {
            if (coordenadas.size() == 1) {
                mapaSalvo.getController().setCenter(coordenadas.get(0));
            } else if (!coordenadas.isEmpty()) {
                mapaSalvo.zoomToBoundingBox(BoundingBox.fromGeoPoints(coordenadas),
                        false, dp(16), 17.0, null);
            }
        });

        LinearLayout conteudo = new LinearLayout(this);
        conteudo.setOrientation(LinearLayout.VERTICAL);
        int altura = Math.min(dp(280), getResources().getDisplayMetrics().heightPixels / 2);
        conteudo.addView(mapaSalvo, new LinearLayout.LayoutParams(-1, altura));
        TextView credito = new TextView(this);
        configurarCreditoMapa(credito);
        conteudo.addView(credito);
        dialogoHistorico = new AlertDialog.Builder(this)
                .setTitle(formatarData(registro.inicioMs))
                .setView(conteudo)
                .setPositiveButton("Fechar", null)
                .create();
        dialogoHistorico.setOnDismissListener(d -> dialogoHistorico = null);
        dialogoHistorico.show();
    }

    /**
     * Executa consultas e gravações na fila do banco para manter a tela responsiva.
     * Entrega o resultado ou a falha na thread da interface enquanto a Activity estiver aberta.
     *
     * @param consulta operação a executar no banco
     * @param sucesso ação que recebe o resultado
     * @param falha ação executada quando ocorre um erro do SQLite
     * @param <T> tipo de resultado produzido pela operação
     */
    private <T> void consultarBanco(Supplier<T> consulta, Consumer<T> sucesso, Runnable falha) {
        filaBanco.execute(() -> {
            Runnable resposta;
            try {
                T resultado = consulta.get();
                resposta = () -> sucesso.accept(resultado);
            } catch (SQLiteException erro) {
                resposta = falha;
            }
            Runnable acao = resposta;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    acao.run();
                }
            });
        });
    }

    /**
     * Aplica a atribuição do OpenStreetMap e o link da licença ao rodapé do mapa.
     * A mesma configuração atende à tela inicial e ao mapa do histórico.
     */
    private void configurarCreditoMapa(TextView credito) {
        credito.setText("© OpenStreetMap contributors");
        credito.setTextSize(12);
        credito.setPaintFlags(credito.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        credito.setOnClickListener(v -> {
            Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"));
            try {
                startActivity(link);
            } catch (android.content.ActivityNotFoundException erro) {
                Toast.makeText(this, "openstreetmap.org/copyright", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Converte unidades independentes da densidade em pixels para dimensionar a interface.
     */
    private int dp(int valor) {
        return Math.round(valor * getResources().getDisplayMetrics().density);
    }

    // View customizada. A biblioteca carrega o mapa e este componente desenha a rota.
    private final class VistaTrajeto extends MapView {
        private final Paint tinta = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path caminho = new Path();
        private final Point pixel = new Point();
        private final GeoPoint coordenada = new GeoPoint(0.0, 0.0);
        private List<Ponto> pontos = new ArrayList<>();
        private GeoPoint posicao;

        /**
         * Cria uma visualização com a fonte do OpenStreetMap e o zoom inicial.
         * Registra a camada responsável por desenhar o trajeto sobre o mapa.
         */
        VistaTrajeto() {
            super(MainActivity.this);
            setTileSource(MAPA_OSM);
            setTilesScaledToDpi(true);
            setVerticalMapRepetitionEnabled(false);
            getController().setZoom(17.0);
            setContentDescription("Mapa do trajeto");
            getOverlays().add(new Overlay() {
                /**
                 * É chamado pela biblioteca para desenhar a camada do trajeto sobre o mapa.
                 */
                @Override
                public void draw(Canvas tela, Projection projecao) {
                    desenharTrajeto(tela, projecao);
                }
            });
        }

        /**
         * Deixa os gestos disponíveis para a rolagem da tela e mantém o mapa estático.
         */
        @Override
        public boolean dispatchTouchEvent(MotionEvent evento) {
            // Mantém o mapa estático e permite rolar a tela.
            return false;
        }

        /**
         * Transforma os pontos do GPS em coordenadas de tela e desenha a linha da rota.
         * Desenha os marcadores correspondentes ao início, ao fim salvo ou à posição atual.
         */
        private void desenharTrajeto(Canvas tela, Projection projecao) {
            caminho.reset();
            for (Ponto ponto : pontos) {
                // Converte latitude e longitude em pixels do mapa.
                coordenada.setCoords(ponto.latitude, ponto.longitude);
                projecao.toPixels(coordenada, pixel);
                if (ponto.novoTrecho) {
                    // Começa outra linha ao retomar a gravação, evitando ligar os trechos.
                    caminho.moveTo(pixel.x, pixel.y);
                } else {
                    caminho.lineTo(pixel.x, pixel.y);
                }
            }
            tinta.setColor(Color.rgb(28, 87, 173));
            tinta.setStrokeWidth(dp(4));
            tinta.setStrokeCap(Paint.Cap.ROUND);
            tinta.setStrokeJoin(Paint.Join.ROUND);
            tinta.setStyle(Paint.Style.STROKE);
            tela.drawPath(caminho, tinta);
            tinta.setStyle(Paint.Style.FILL);

            // Verde marca o início, vermelho o fim salvo e azul a posição atual.
            if (!pontos.isEmpty()) {
                Ponto inicio = pontos.get(0);
                marcador(tela, projecao, new GeoPoint(inicio.latitude, inicio.longitude), 0xff167044);
                if (posicao == null && pontos.size() > 1) {
                    Ponto fim = pontos.get(pontos.size() - 1);
                    marcador(tela, projecao, new GeoPoint(fim.latitude, fim.longitude), 0xffa9322a);
                }
            }
            if (posicao != null) {
                marcador(tela, projecao, posicao, 0xff1c57ad);
            }
        }

        /**
         * Desenha um ponto colorido com contorno branco na coordenada indicada.
         */
        private void marcador(Canvas tela, Projection projecao, GeoPoint local, int cor) {
            projecao.toPixels(local, pixel);
            tinta.setColor(Color.WHITE);
            tela.drawCircle(pixel.x, pixel.y, dp(7), tinta);
            tinta.setColor(cor);
            tela.drawCircle(pixel.x, pixel.y, dp(5), tinta);
        }
    }
}
