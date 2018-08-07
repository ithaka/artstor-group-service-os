# Introduction to artstor-group-service

Artstor provides various tools to organize content on AIW workspace.

Image group can be defined as a logical grouping of image,media

Artstor group service provides various CRUD methods for dealing with a group of images/objects. Also, this service supports sharing a group using tokens.

Technical Requirements:-

1. Elastic Search: a search tool to search groups by name or descriptive content.

2. Postgres database: to support create, read, update, and delete functions. Alternatively, the schema can be located on other relational databases.

3. Metadata service: can serve metadata for images identified by groups.

4. Oracle database: one optional source for metadata.

Library Dependencies:-
1. org.slf4j, org.apache.logging.log4j and ring logger libraries.
2. elastic search client clojurewerkz/elastisch.
3. Sql libraries yesql, ojdbc7, and org.postgresql/postgresql.
4. ragtime for setting up test database.
5. web api and swagger done using metosin/compojure-api.

