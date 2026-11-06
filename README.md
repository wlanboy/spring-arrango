# Spring Boot template
Spring 4 + webmvc + actuator + arrangodb client

Minimal Spring Boot service that connects to ArangoDB via Spring Data ArangoDB. It contains an example `Connection` document (collection `connections`) with a repository, an actuator health indicator for the ArangoDB connection, and a lean Tomcat configuration.

## Configuration
| Variable | Default |
|---|---|
| `ARANGODB_HOST` | `localhost` |
| `ARANGODB_PORT` | `8529` |
| `ARANGODB_USER` | `root` |
| `ARANGODB_PASSWORD` | _(empty)_ |
| `ARANGODB_DATABASE` | `arrango` |
| `SERVER_PORT` | `8080` |

Health: `GET /actuator/health`

