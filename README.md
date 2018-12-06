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

The followig configuration options are optional.

| Environment Variable  | Description                                                                                                                             |
| --------------------  | --------------------------------------------------------------------------------------------------------------------------------------- |
| CALLER_KEY            | The caller key that WiseTime should provide with post time webhook calls. The connector does not authenticate Webhook calls if not set. |
| TAG_UPSERT_PATH       | The tag folder path to use for Jira tags. Defaults to `/Jira`.                                                                          |
| TAG_UPSERT_BATCH_SIZE | Number of tags to upsert at a time. A large batch size mitigates API call latency. Defaults to 500.                                     |

The connector needs to be able to read from the `project` and `jiraissue` tables, and write to the `worklog` and `sequence_value_item` tables of the Jira database.

## Running the WiseTime Jira Connector

The easiest way to run the Jira Connector is using Docker. For example:

```text
docker run -d \
    -p 80:80 \
    --restart=unless-stopped \
    -e API_KEY=yourwisetimeapikey \
    -e JIRA_JDBC_URL="jdbc:mysql://host:port/jira_database?useUnicode=true&characterEncoding=UTF8&useSSL=false" \
    -e JIRA_DB_USER=dbuser \
    -e JIRA_DB_PASSWORD=dbpass \
    wisetime/jira-connector
```

The Jira connector runs self-checks to determine whether it is healthy. If health check fails, the connector will shutdown. This gives us a chance to automatically re-initialise the application through the Docker restart policy.

## Logging to AWS CloudWatch

If configured, the Jira Connector can send application logs to [AWS CloudWatch](https://aws.amazon.com/cloudwatch/). In order to do so, you must supply the following configuration through the following environment variables.

| Environment Variable  | Description                                          |
| --------------------- | ---------------------------------------------------- |
| AWS_ACCESS_KEY_ID     | AWS access key for account with access to CloudWatch |
| AWS_SECRET_ACCESS_KEY | Secret for the AWS access key                        |
| AWS_DEFAULT_REGION    | AWS region to log to                                 |

## Building

To build a Docker image of the WiseTime Jira Connector, run:

```text
make docker
```
