-- =====================================================================================
-- V4 — Idempotence des paiements, ancrages Merkle, billets à usage unique
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1. Idempotence — correction du double débit
-- -------------------------------------------------------------------------------------
-- Kafka garantit une livraison « au moins une fois » : si l'accusé est perdu, le message est
-- rejoué. Sans traitement idempotent, le rejeu produit un second débit réel sur la carte du
-- supporter. La garantie « exactement une fois » n'existe pas au niveau du transport ; elle
-- s'obtient en rendant le TRAITEMENT idempotent.
--
-- Les clés vivent en PostgreSQL et non en Redis : Redis est volatil par défaut, et un
-- redémarrage ou une éviction perd la clé puis rouvre la fenêtre de double débit. L'insertion
-- se fait dans la MÊME transaction que l'effet métier, sinon la garantie est illusoire.
CREATE TABLE idempotency_record (
    idempotency_key  TEXT         PRIMARY KEY,
    operation        TEXT         NOT NULL,
    request_digest   BYTEA        NOT NULL,
    response_payload JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT idempotency_operation
        CHECK (operation IN ('CAPTURE_PAYMENT', 'RELEASE_AUTH', 'ISSUE_TICKET', 'REFUND'))
);

CREATE INDEX ix_idempotency_created ON idempotency_record (created_at);

COMMENT ON COLUMN idempotency_record.request_digest IS
    'Condensé de la requête. Une même clé réutilisée avec une charge différente est un bug appelant, pas un rejeu : à rejeter explicitement.';

-- -------------------------------------------------------------------------------------
-- 2. Ancrages Merkle — correction de l'intégrité réinscriptible
-- -------------------------------------------------------------------------------------
-- Une chaîne de hash n'interdit que la modification LOCALE d'un maillon. L'exploitant, qui
-- détient les entrées et l'algorithme, peut reconstruire la chaîne entière hors ligne avec les
-- déclarations de son choix : le résultat est parfaitement cohérent et totalement falsifié.
--
-- La racine est donc publiée périodiquement et horodatée par un tiers (RFC 3161) PENDANT la
-- fenêtre. C'est ce qui prive l'exploitant de l'enchère fantôme — le problème phare de Vickrey :
-- une attaque autrement profitable, invisible et indémontrable.
CREATE TABLE merkle_anchor (
    id               BIGSERIAL    PRIMARY KEY,
    match_id         UUID         NOT NULL REFERENCES match (id),
    tree_size        INT          NOT NULL,
    root_hash        BYTEA        NOT NULL,

    -- Jeton d'horodatage délivré par l'autorité tierce. Sa présence est ce qui transforme la
    -- publication en preuve : sans ancrage externe daté, l'audit ne prouve rien contre
    -- l'adversaire interne, qui est précisément l'adversaire pertinent ici.
    tsa_token        BYTEA,
    tsa_authority    TEXT,
    anchored_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_url    TEXT,

    CONSTRAINT merkle_anchor_root_len CHECK (octet_length(root_hash) = 32),
    CONSTRAINT merkle_anchor_size CHECK (tree_size >= 0),
    CONSTRAINT uq_merkle_anchor_size UNIQUE (match_id, tree_size)
);

CREATE INDEX ix_merkle_anchor_match ON merkle_anchor (match_id, anchored_at DESC);

-- Le nombre d'engagements est public avant la révélation : une déclaration surnuméraire
-- apparue au calcul devient arithmétiquement détectable.
CREATE VIEW v_commitment_count AS
SELECT match_id, count(*) AS total_commitments, max(commit_seq) AS highest_seq
FROM bid_commitment
GROUP BY match_id;

-- -------------------------------------------------------------------------------------
-- 3. Billets — correction du rejeu de QR code
-- -------------------------------------------------------------------------------------
-- Une signature atteste « ce billet a bien été émis par nous ». Elle n'atteste ni qu'il est
-- récent, ni qu'il n'a pas déjà servi. Un QR signé statique est un jeton au porteur, copiable
-- à l'infini : le premier arrivé entre, les autres sont refusés à la porte.
--
-- Deux remèdes combinés : fraîcheur par QR rotatif de type TOTP, et état serveur par
-- consommation à usage unique.
CREATE TABLE ticket (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id        UUID         NOT NULL REFERENCES match (id),
    user_id         UUID         NOT NULL REFERENCES app_user (id),
    category_id     UUID         NOT NULL REFERENCES seat_category (id),
    bid_commitment_id BIGINT     REFERENCES bid_commitment (id),
    price_paid      NUMERIC(12,2) NOT NULL,

    -- Graine du QR rotatif. Jamais transmise en clair hors de l'appareil appairé ; le client
    -- en dérive un code à durée de vie de 30 secondes, après déverrouillage biométrique.
    totp_secret     BYTEA        NOT NULL,

    -- Consommation à usage unique. L'invalidation est atomique au premier scan.
    consumed_at     TIMESTAMPTZ,
    consumed_gate   TEXT,
    status          TEXT         NOT NULL DEFAULT 'ISSUED',
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ticket_status
        CHECK (status IN ('ISSUED', 'CONSUMED', 'REVOKED', 'BOUGHT_BACK')),
    CONSTRAINT ticket_price CHECK (price_paid >= 0),
    CONSTRAINT ticket_consumed_coherent
        CHECK ((status = 'CONSUMED') = (consumed_at IS NOT NULL))
);

-- Un seul billet actif par supporter et par match : ferme la multi-émission.
CREATE UNIQUE INDEX uq_ticket_active
    ON ticket (match_id, user_id)
    WHERE status IN ('ISSUED', 'CONSUMED');

-- Journal des scans, y compris refusés : c'est la trace qui permet de distinguer une copie
-- d'écran diffusée d'un incident de lecteur.
CREATE TABLE gate_scan_attempt (
    id          BIGSERIAL    PRIMARY KEY,
    ticket_id   UUID         REFERENCES ticket (id),
    gate_code   TEXT         NOT NULL,
    outcome     TEXT         NOT NULL,
    scanned_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT gate_scan_outcome
        CHECK (outcome IN ('ACCEPTED', 'ALREADY_CONSUMED', 'EXPIRED_CODE', 'UNKNOWN', 'REVOKED'))
);

CREATE INDEX ix_gate_scan_ticket ON gate_scan_attempt (ticket_id, scanned_at DESC);
