# WiseTime Jira Connector

The WiseTime Jira Connector connects [WiseTime](https://wisetime.io) to [Jira](https://www.atlassian.com/software/jira), and will automatically:

* Create a new WiseTime tag whenever a new Jira issue is created
* Record a work log entry against the matching Jira issue whenever a user posts time to WiseTime
* Update the Jira issue total worked time when posted time is received

In order to use the WiseTime Jira Connector, you will need a [WiseTime Connect](https://wisetime.io/docs/connect/) API key. The WiseTime Jira Connector runs as a Docker container and is easy to set up and operate.

## Configuration

Configuration is done through environment variables. The following configuration options are required.

| Environment Variable  | Description                                     |
| --------------------  | ----------------------------------------------- |
| API_KEY               | Your WiseTime Connect API Key                   |
| JIRA_JDBC_URL         | The JDBC URL for your Jira database             |
| JIRA_DB_USER          | Username to use to connect to the Jira database |
| JIRA_DB_PASSWORD      | Password to use to connect to the Jira database |

The following configuration options are optional.


| Environment Variable  | Description                                                                                                                                                                                                                           |
| --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| CALLER_KEY            | The caller key that WiseTime should provide with post time webhook calls. The connector does not authenticate Webhook calls if not set.                                                                                               |
| TAG_UPSERT_PATH       | The WiseTime tag folder path to use for Jira tags. Defaults to `/Jira/` (trailing slash is required). Use `/` for root folder.                                                                                                        |
| TAG_UPSERT_BATCH_SIZE | Number of tags to upsert at a time. A large batch size mitigates API call latency. Defaults to 500.                                                                                                                                   |
| PROJECT_KEYS_FILTER   | If set, the connector will only handle Jira issues from the configured Jira project keys.                                                                                                                                             |
| DATA_DIR              | If set, the connector will use the directory as the location for storing data to keep track on the Jira issues it has synced. By default, WiseTime Connector will create a temporary dir under `/tmp` as its data storage.            |
| TIMEZONE              | The timezone to use when posting time to Jira if the default timezone is not available in Jira's database, e.g. `Australia/Perth`. Defaults to `UTC`.                                                                                 |
| RECEIVE_POSTED_TIME   | If unset, this defaults to `LONG_POLL`: use long polling to fetch posted time. Optional parameters are `WEBHOOK` to start up a server to listen for posted time. `DISABLED` no handling for posted time                               |
| TAG_SCAN              | If unset, this defaults to `ENABLED`: Set mode for scanning external system for tags and uploading to WiseTime. Possible values: ENABLED, DISABLED.                                                                                   |
| WEBHOOK_PORT          | The connector will listen to this port e.g. 8090, if RECEIVE_POSTED_TIME is set to `WEBHOOK`. Defaults to 8080.                                                                                                                       |
| LOG_LEVEL             | Define log level. Available values are: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` and `OFF`. Default is `INFO`.                                                                                                                       |

The connector needs to be able to read from the `project` and `jiraissue` tables, and write to the `worklog` and `sequence_value_item` tables of the Jira database.

## Running the WiseTime Jira Connector

The easiest way to run the Jira Connector is using Docker. For example:

```text
docker run -d \
    --restart=unless-stopped \
    -v volume_name:/usr/local/wisetime-connector/data \
    -e DATA_DIR=/usr/local/wisetime-connector/data \
    -e API_KEY=yourwisetimeapikey \
    -e JIRA_JDBC_URL="jdbc:mysql://host:port/jira_database?useUnicode=true&characterEncoding=UTF8&useSSL=false" \
    -e JIRA_DB_USER=dbuser \
    -e JIRA_DB_PASSWORD=dbpass \
    wisetime/wisetime-jira-connector
```

If you are using `CONNECTOR_MODE=WEBHOOK`: Note that you need to define port forwarding in the docker run command (and similarly any docker-compose.yaml definition). If you set the webhook port other than default (8080) you must also add the WEBHOOK_PORT environment variable to match the docker ports definition.

The Jira connector runs self-checks to determine whether it is healthy. If health check fails, the connector will shutdown. This gives us a chance to automatically re-initialise the application through the Docker restart policy.

## Logging

#### Common

Jira Connector uses [logback](https://logback.qos.ch) as logging framework. Default log level is `INFO`, you can change it by setting `LOG_LEVEL` configuration.

To setup own appenders or add another customization you can add `logback-extra.xml` on classpath. For more information see [File inclusion](https://logback.qos.ch/manual/configuration.html#fileInclusion).

#### Logging to AWS CloudWatch

If configured, the Jira Connector can send application logs to [AWS CloudWatch](https://aws.amazon.com/cloudwatch/). In order to do so, you must supply the following configuration through the following environment variables.

| Environment Variable  | Description                                          |
| --------------------- | ---------------------------------------------------- |
| AWS_ACCESS_KEY_ID     | AWS access key for account with access to CloudWatch |
| AWS_SECRET_ACCESS_KEY | Secret for the AWS access key                        |
| AWS_REGION            | AWS region to log to                                 |

## Building

To build a Docker image of the WiseTime Jira Connector, run:

```text
make docker
```
