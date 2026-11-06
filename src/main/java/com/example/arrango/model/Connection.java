package com.example.arrango.model;

import com.arangodb.springframework.annotation.ArangoId;
import com.arangodb.springframework.annotation.Document;
import org.springframework.data.annotation.Id;

@Document("connections")
public class Connection {

	@Id
	private String id;

	@ArangoId
	private String arangoId;

	private String name;

	private int age;

	public Connection() {
	}

	public Connection(String name, int age) {
		this.name = name;
		this.age = age;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getArangoId() {
		return arangoId;
	}

	public void setArangoId(String arangoId) {
		this.arangoId = arangoId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public int getAge() {
		return age;
	}

	public void setAge(int age) {
		this.age = age;
	}
}
