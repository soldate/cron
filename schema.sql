CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS cron;

CREATE TABLE IF NOT EXISTS cron.tarefa (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nome               TEXT NOT NULL,
    url                TEXT NOT NULL,
    metodo             VARCHAR(10) NOT NULL DEFAULT 'POST',
    headers            JSONB NOT NULL DEFAULT '{}'::jsonb,
    payload            JSONB NOT NULL DEFAULT '{}'::jsonb,
    tipo_agendamento   VARCHAR(20) NOT NULL DEFAULT 'intervalo',
    intervalo_minutos  INTEGER,
    executar_em        TIMESTAMP WITHOUT TIME ZONE,
    proxima_execucao   TIMESTAMP WITHOUT TIME ZONE DEFAULT date_trunc('minute', now()),
    ultima_execucao    TIMESTAMP WITHOUT TIME ZONE,
    ativo              BOOLEAN NOT NULL DEFAULT TRUE,
    timeout_segundos   INTEGER NOT NULL DEFAULT 30,
    max_tentativas     INTEGER NOT NULL DEFAULT 1,
    retry_intervalo_minutos INTEGER NOT NULL DEFAULT 1,
    tentativas_feitas  INTEGER NOT NULL DEFAULT 0,
    criado_em          TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    atualizado_em      TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CHECK (metodo IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    CHECK (tipo_agendamento IN ('intervalo', 'unico')),
    CHECK (
        (tipo_agendamento = 'intervalo' AND intervalo_minutos IS NOT NULL AND intervalo_minutos >= 1)
        OR
        (tipo_agendamento = 'unico' AND executar_em IS NOT NULL)
    ),
    CHECK (timeout_segundos BETWEEN 1 AND 300),
    CHECK (max_tentativas BETWEEN 1 AND 20),
    CHECK (retry_intervalo_minutos BETWEEN 1 AND 1440)
);

ALTER TABLE cron.tarefa
    ADD COLUMN IF NOT EXISTS retry_intervalo_minutos INTEGER NOT NULL DEFAULT 1;

CREATE TABLE IF NOT EXISTS cron.execucao (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tarefa_id          UUID NOT NULL REFERENCES cron.tarefa(id) ON DELETE CASCADE,
    iniciada_em        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalizada_em      TIMESTAMP WITHOUT TIME ZONE,
    duracao_ms         INTEGER,
    sucesso            BOOLEAN NOT NULL DEFAULT FALSE,
    status_http        INTEGER,
    erro               TEXT,
    resposta_body      TEXT,
    resposta_headers   JSONB,
    tentativa          INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_cron_tarefa_proxima ON cron.tarefa (ativo, proxima_execucao);
CREATE INDEX IF NOT EXISTS idx_cron_tarefa_tipo ON cron.tarefa (tipo_agendamento);
CREATE INDEX IF NOT EXISTS idx_cron_execucao_tarefa ON cron.execucao (tarefa_id, iniciada_em DESC);
CREATE INDEX IF NOT EXISTS idx_cron_execucao_iniciada ON cron.execucao (iniciada_em DESC);
