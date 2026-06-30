# cron

## Dona360

Este projeto faz parte do sistema maior `Dona360`, composto por modulos separados que rodam como servicos independentes, mas compartilham a mesma base PostgreSQL `piloto`.

Politica de banco:

- cada modulo tem seu proprio usuario de aplicacao e schema principal;
- todos os usuarios de aplicacao devem ser membros da role comum `piloto_app`;
- `piloto_app` deve ter acesso de leitura e escrita a todos os schemas de negocio do banco `piloto`;
- portanto, os modulos nao devem depender de isolamento por schema para acessar dados do Dona360.

Modulo de agendamento de tarefas do Dona360.


Servico Java/Jetty para agendar e executar chamadas HTTP em intervalos de minuto.

## Desenvolvimento

```bash
mvn -s .vscode/maven-local-settings.xml clean package
```

Execucao local:

```bash
DB_URL=jdbc:postgresql://localhost:5432/piloto \
DB_USER=dona_cron \
DB_PASSWORD=... \
PORT=8086 \
WORKER_INTERVAL_SECONDS=60 \
java -jar target/cron-service-0.0.1-SNAPSHOT.jar
```

## Endpoints

- `GET /health`
- `GET /api/cron/tasks`
- `POST /api/cron/tasks`
- `POST /api/cron/tasks/<id>/run`
- `GET /api/cron/tasks/<id>/executions`
- `GET /api/cron/executions`

Em producao pelo Apache:

- `GET https://dona360.com.br/cron/health`
- `GET https://dona360.com.br/cron/api/cron/tasks`
- `POST https://dona360.com.br/cron/api/cron/tasks`

Servico systemd em producao: `dona-cron.service`.

## Criar tarefa

Intervalo:

```bash
curl -X POST http://127.0.0.1:8086/api/cron/tasks \
  -H 'Content-Type: application/json' \
  -d '{
    "nome": "teste",
    "url": "https://example.com/webhook",
    "metodo": "POST",
    "payload": {"ok": true},
    "tipo_agendamento": "intervalo",
    "intervalo_minutos": 5
  }'
```

Execucao unica:

```json
{
  "nome": "execucao unica",
  "url": "https://example.com/webhook",
  "payload": {"evento": "teste"},
  "tipo_agendamento": "unico",
  "executar_em": "2026-06-30T15:00:00"
}
```

## Banco

```text
database: piloto
schema: cron
usuario sugerido: dona_cron
```

Aplicar schema:

```bash
DB_PASSWORD=... scripts/apply_schema.sh
```
