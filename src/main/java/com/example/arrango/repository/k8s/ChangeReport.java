package com.example.arrango.repository.k8s;

import java.util.List;

/**
 * Änderungen seit einem Zeitpunkt.
 *
 * @param added Ressourcen, die ein Import seit {@code since} zum ersten Mal gesehen hat
 * @param stale Ressourcen, die seit {@code since} kein Import mehr gesehen hat. Normalerweise entfernt der Import
 *              solche Dokumente; übrig bleiben die aus Namespaces oder Collections, die nicht lesbar waren oder
 *              außerhalb von {@code k8s.import.namespaces} liegen.
 */
public record ChangeReport(String since, List<SeenRef> added, List<SeenRef> stale) {
}
