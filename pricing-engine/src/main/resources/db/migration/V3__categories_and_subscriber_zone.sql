-- =====================================================================================
-- V3 — Catégories de places et zone abonnés
-- =====================================================================================
-- Corrige la faille d'homogénéité : la version initiale appliquait un prix unique à toutes
-- les places, ce qui est économiquement faux et perçu comme injuste entre une tribune
-- d'honneur et un virage haut.
-- =====================================================================================

CREATE TABLE seat_category (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    stadium_id    UUID        NOT NULL REFERENCES stadium (id),
    code          TEXT        NOT NULL,
    label_fr      TEXT        NOT NULL,
    label_ar      TEXT        NOT NULL,

    -- Ordre de désirabilité, 1 = meilleure. Publié à l'avance avec le match : un supporter
    -- doit pouvoir vérifier la règle d'affectation avant de soumettre sa déclaration.
    desirability_rank INT     NOT NULL,
    physical_capacity INT     NOT NULL,

    CONSTRAINT uq_category_code UNIQUE (stadium_id, code),
    CONSTRAINT uq_category_rank UNIQUE (stadium_id, desirability_rank),
    CONSTRAINT seat_category_rank_positive CHECK (desirability_rank >= 1),
    CONSTRAINT seat_category_capacity_positive CHECK (physical_capacity >= 0)
);

-- Zone abonnés : un nombre de places fixe, dans une zone dédiée, réservé aux supporters
-- fidèles porteurs d'une carte de saison. Ces places sortent entièrement du mécanisme
-- d'enchère et sont vendues au prix fixe `subscriber_fixed_price`.
CREATE TABLE subscriber_zone (
    id               UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id         UUID    NOT NULL REFERENCES match (id),
    category_id      UUID    NOT NULL REFERENCES seat_category (id),
    reserved_seats   INT     NOT NULL,
    fixed_price      NUMERIC(12,2) NOT NULL,

    -- Échéance de confirmation, strictement antérieure à la clôture de la fenêtre.
    -- Sans elle, les places d'abonnés absents restent vides alors que des supporters ont été
    -- évincés au prix de clôture : perte de recette et de garnissage, visible à la télévision.
    confirm_deadline TIMESTAMPTZ NOT NULL,
    confirmed_seats  INT     NOT NULL DEFAULT 0,
    reconciled_at    TIMESTAMPTZ,

    CONSTRAINT uq_subscriber_zone UNIQUE (match_id, category_id),
    CONSTRAINT subscriber_zone_seats CHECK (reserved_seats >= 0),
    CONSTRAINT subscriber_zone_confirmed CHECK (confirmed_seats BETWEEN 0 AND reserved_seats),
    CONSTRAINT subscriber_zone_price CHECK (fixed_price >= 0)
);

-- Capacité du pool dynamique par catégorie, figée à la réconciliation.
-- dynamic_capacity = physical_capacity − places abonnés effectivement confirmées.
-- Les places non confirmées sont réintégrées au pool AVANT la clôture, de sorte que N soit
-- définitif au moment du calcul du prix.
CREATE TABLE dynamic_pool (
    id               UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id         UUID    NOT NULL REFERENCES match (id),
    category_id      UUID    NOT NULL REFERENCES seat_category (id),
    dynamic_capacity INT     NOT NULL,
    reserve_price    NUMERIC(12,2) NOT NULL,

    -- Prix de clôture, écrit une seule fois par le moteur d'allocation.
    clearing_price   NUMERIC(12,2),
    computed_at      TIMESTAMPTZ,

    CONSTRAINT uq_dynamic_pool UNIQUE (match_id, category_id),
    CONSTRAINT dynamic_pool_capacity CHECK (dynamic_capacity >= 0),
    CONSTRAINT dynamic_pool_reserve CHECK (reserve_price >= 0),
    -- Le prix de clôture ne descend jamais sous la réserve.
    CONSTRAINT dynamic_pool_clearing_above_reserve
        CHECK (clearing_price IS NULL OR clearing_price >= reserve_price)
);

-- Garde-fou : la clôture ne peut pas précéder l'échéance de confirmation des abonnés.
ALTER TABLE match
    ADD CONSTRAINT match_confirm_before_close
    CHECK (bidding_closes_at > bidding_opens_at);

COMMENT ON TABLE subscriber_zone IS
    'Places à prix fixe réservées aux abonnés de la saison, hors mécanisme d''enchère.';
COMMENT ON COLUMN dynamic_pool.dynamic_capacity IS
    'N_k définitif, après réintégration des places abonnés non confirmées à l''échéance.';
