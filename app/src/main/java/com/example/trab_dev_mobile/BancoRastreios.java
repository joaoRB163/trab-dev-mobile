package com.example.trab_dev_mobile;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class BancoRastreios extends SQLiteOpenHelper {
    BancoRastreios(Context context) {
        super(context.getApplicationContext(), "rastreios.db", null, 1);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE rastreios ("
                + "id INTEGER PRIMARY KEY, inicio_ms INTEGER NOT NULL, "
                + "distancia_m REAL NOT NULL, duracao_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE pontos ("
                + "rastreio_id INTEGER NOT NULL REFERENCES rastreios(id) ON DELETE CASCADE, "
                + "ordem INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, "
                + "elevacao_m REAL, novo_trecho INTEGER NOT NULL, "
                + "PRIMARY KEY (rastreio_id, ordem))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int versaoAntiga, int versaoNova) {
        throw new IllegalStateException("Migração SQLite ainda não definida.");
    }

    // Grava o registro e seus pontos na mesma transação.
    long salvar(long inicioMs, double distanciaM, long duracaoMs, List<Ponto> pontos) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues registro = new ContentValues();
            registro.put("inicio_ms", inicioMs);
            registro.put("distancia_m", distanciaM);
            registro.put("duracao_ms", duracaoMs);
            long id = db.insertOrThrow("rastreios", null, registro);

            ContentValues valores = new ContentValues();
            valores.put("rastreio_id", id);
            for (int i = 0; i < pontos.size(); i++) {
                Ponto ponto = pontos.get(i);
                valores.put("ordem", i);
                valores.put("latitude", ponto.latitude);
                valores.put("longitude", ponto.longitude);
                if (ponto.elevacaoM == null) {
                    valores.putNull("elevacao_m");
                } else {
                    valores.put("elevacao_m", ponto.elevacaoM);
                }
                valores.put("novo_trecho", ponto.novoTrecho ? 1 : 0);
                db.insertOrThrow("pontos", null, valores);
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    List<Registro> listar() {
        List<Registro> registros = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id, inicio_ms, distancia_m, duracao_ms "
                        + "FROM rastreios ORDER BY inicio_ms DESC, id DESC", null)) {
            while (cursor.moveToNext()) {
                registros.add(new Registro(cursor.getLong(0), cursor.getLong(1),
                        cursor.getDouble(2), cursor.getLong(3)));
            }
        }
        return registros;
    }

    List<Ponto> carregarPontos(long registroId) {
        List<Ponto> pontos = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT latitude, longitude, elevacao_m, novo_trecho "
                        + "FROM pontos WHERE rastreio_id = ? ORDER BY ordem",
                new String[]{Long.toString(registroId)})) {
            while (cursor.moveToNext()) {
                Double elevacao = cursor.isNull(2) ? null : cursor.getDouble(2);
                pontos.add(new Ponto(cursor.getDouble(0), cursor.getDouble(1),
                        elevacao, cursor.getInt(3) != 0));
            }
        }
        return pontos;
    }

    static double velocidadeMediaKmh(double distanciaM, long duracaoMs) {
        return duracaoMs > 0 ? distanciaM * 3600 / duracaoMs : 0;
    }

    static final class Registro {
        final long id;
        final long inicioMs;
        final double distanciaM;
        final long duracaoMs;

        Registro(long id, long inicioMs, double distanciaM, long duracaoMs) {
            this.id = id;
            this.inicioMs = inicioMs;
            this.distanciaM = distanciaM;
            this.duracaoMs = duracaoMs;
        }
    }

    static final class Ponto {
        final double latitude;
        final double longitude;
        final Double elevacaoM;
        final boolean novoTrecho;

        Ponto(double latitude, double longitude, Double elevacaoM, boolean novoTrecho) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.elevacaoM = elevacaoM;
            this.novoTrecho = novoTrecho;
        }
    }
}
