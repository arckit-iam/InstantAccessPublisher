// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.agent.domain.model;

/**
 * Contexte optionnel de la ressource cible de l'événement IAM.
 *
 * <p>Intentionnellement non-contraint : le type et la référence sont des chaînes libres
 * car les patterns varient selon la source (AD, badge, application…) et ne peuvent pas
 * être normalisés à ce niveau (topic {@code iam.raw.*}).
 *
 * <p>Exemples :
 * <pre>
 *   new ResourceContext("server",      "DC01")
 *   new ResourceContext("badge",       "badgeId=20")
 *   new ResourceContext("application", "SX123456")
 *   new ResourceContext("workstation", "PC-ETIEN-01")
 * </pre>
 *
 * @param type Catégorie de la ressource  (ex: "server", "badge", "application", "workstation")
 * @param ref  Identifiant libre dans son système source (aucun format imposé)
 */
public record ResourceContext(String type, String ref) {

    public ResourceContext {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("ResourceContext.type must not be blank");
        if (ref  == null || ref.isBlank())  throw new IllegalArgumentException("ResourceContext.ref must not be blank");
    }
}
