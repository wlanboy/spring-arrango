package com.example.arrango.model.k8s;

import java.util.List;

/** Ein Eintrag aus {@code spec.selector.matchExpressions} eines Workloads, z. B. {@code tier In (web, api)}. */
public class SelectorRequirement {

	private String key;

	/** {@code In}, {@code NotIn}, {@code Exists} oder {@code DoesNotExist}. */
	private String operator;

	/** Leer bei {@code Exists} und {@code DoesNotExist}. */
	private List<String> values;

	public SelectorRequirement() {
	}

	public SelectorRequirement(String key, String operator, List<String> values) {
		this.key = key;
		this.operator = operator;
		this.values = values;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public String getOperator() {
		return operator;
	}

	public void setOperator(String operator) {
		this.operator = operator;
	}

	public List<String> getValues() {
		return values;
	}

	public void setValues(List<String> values) {
		this.values = values;
	}
}
