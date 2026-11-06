package com.example.arrango.repository;

import com.arangodb.springframework.repository.ArangoRepository;
import com.example.arrango.model.Connection;

import java.util.List;

public interface ConnectionRepository extends ArangoRepository<Connection, String> {

	List<Connection> findByName(String name);
}
