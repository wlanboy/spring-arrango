package com.example.arrango.repository.k8s;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Schlanke Projektion einer beliebigen Ressource als Ergebnis von Graph-Abfragen. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceRef(String id, String kind, String namespace, String name, Integer depth) {
}
