package com.example.arrango.repository.k8s;

/** Schlanke Projektion einer beliebigen Ressource als Ergebnis von Graph-Abfragen. */
public record ResourceRef(String id, String kind, String namespace, String name, Integer depth) {
}
