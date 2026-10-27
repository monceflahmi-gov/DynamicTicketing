-- =====================================================================================
-- V2 — Journal d'engagements en ajout seul (append-only transparency log)
-- =====================================================================================
-- Remplace la table `bid` d'origine, qui stockait les montants en clair : une enchère
-- « scellée » lisible par le DBA n'est pas scellée, et l'hypothèse qui fait tenir la preuve
-- de Vickrey était violée pour tout l'intérieur du système.
--
-- Principe retenu : journal en ajout seul inspiré de Certificate Transparency. Une
-- modification n'écrase jamais rien ; elle insère un nouvel engagement et marque l'ancien
-- comme remplacé. Trois conséquences :
--
--   1. La règle de priorité temporelle est portée par `commit_seq`, un BIGSERIAL strictement
--      monotone attribué par la base. Priorité au premier ayant posé sa demande, et une
--      modification obtient une séquence plus élevée donc perd sa priorité. Cela ferme la
--      faille du sniping : sans cette règle, chacun déposerait un engagement bidon à
--      l'ouverture pour réserver son rang, puis le corrigerait à la dernière seconde.
--   2. Aucune horloge applicative n'intervient dans le classement, donc ni dérive NTP ni
--      recul d'horloge ne peuvent produire deux déclarations de même rang.
--   3. L'arbre de Merkle est construit sur toutes les feuilles jamais insérées. L'arbre ne
--      fait que croître, donc une preuve d'inclusion remise à un supporter reste valide même
--      après que d'autres ont modifié leur déclaration.
-- =====================================================================================

CREATE TABLE bid_commitment (
    id                  BIGSERIAL    PRIMARY KEY,
    commit_seq          BIGSERIAL    NOT NULL UNIQUE,
    match_id            UUID         NOT NULL REFERENCES match (id),

    -- Pseudonyme déterministe de la CIN : HMAC-SHA256 dont la clé réside dans le KMS et ne
    -- sort jamais. Un simple SHA-256 salé globalement ne protégeait rien : l'espace des CIN
    -- marocaines compte de l'ordre de 10^8 à 10^9 candidats, soit quelques secondes de GPU.
    cin_pseudonym       BYTEA        NOT NULL,
    user_id             UUID         NOT NULL REFERENCES app_user (id),

    -- Phase 1 : seul l'engagement est stocké pendant la fenêtre.
    commitment          BYTEA        NOT NULL,
    committed_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Phase 2 : renseigné après la clôture, à la révélation.
    revealed_amount     NUMERIC(12,2),
    revealed_nonce      BYTEA,
    revealed_categories TEXT,
    revealed_at         TIMESTAMPTZ,
    reveal_status       TEXT         NOT NULL DEFAULT 'PENDING',

    -- Chaînage et remplacement.
    superseded_by       BIGINT       REFERENCES bid_commitment (id),
    deposit_forfeited   BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Référence d'autorisation de paiement. Capturée à l'engagement, libérée ou débitée
    -- après la clôture. La fenêtre de déclaration est volontairement courte (48–72 h) :
    -- une autorisation bancaire expire en quelques jours, et une fenêtre de trois semaines
    -- produirait des captures refusées découvertes la veille du match.
    payment_auth_ref    TEXT,

    CONSTRAINT bid_commitment_commitment_len CHECK (octet_length(commitment) = 32),
    CONSTRAINT bid_commitment_reveal_status
        CHECK (reveal_status IN ('PENDING', 'VALID', 'MISMATCH', 'NOT_REVEALED')),
    CONSTRAINT bid_commitment_amount_positive
        CHECK (revealed_amount IS NULL OR revealed_amount > 0),
    -- Une révélation valide implique que les trois champs révélés sont présents.
    CONSTRAINT bid_commitment_reveal_complete CHECK (
        reveal_status <> 'VALID'
        OR (revealed_amount IS NOT NULL AND revealed_nonce IS NOT NULL AND revealed_at IS NOT NULL)
    )
);

-- Une seule déclaration active par identité et par match. L'index est partiel : les
-- engagements remplacés ne comptent pas, ce qui autorise la modification tout en interdisant
-- les déclarations concurrentes.
CREATE UNIQUE INDEX uq_bid_active_per_cin
    ON bid_commitment (match_id, cin_pseudonym)
    WHERE superseded_by IS NULL;

-- Ordre de construction de l'arbre de Merkle : la séquence d'engagement, et rien d'autre.
CREATE INDEX ix_bid_merkle_order
    ON bid_commitment (match_id, commit_seq);

-- Index de classement après révélation. C'est lui qui permet de calculer les statistiques
-- d'ordre sans jamais charger les déclarations en mémoire applicative : un parcours d'index
-- déjà trié, au lieu des 200 000 entités Hibernate qui provoquaient un OutOfMemoryError
-- au moment précis où le système ne doit pas échouer.
CREATE INDEX ix_bid_ranking
    ON bid_commitment (match_id, revealed_amount DESC, commit_seq ASC)
    WHERE superseded_by IS NULL AND reveal_status = 'VALID';

COMMENT ON COLUMN bid_commitment.commit_seq IS
    'Autorité de priorité temporelle. Monotone, attribué par la base, jamais par une horloge applicative.';
COMMENT ON COLUMN bid_commitment.commitment IS
    'HMAC-SHA256(nonce, BIDv1|matchId|bidderId|montant|categories). Le montant est invisible jusqu''à la révélation.';
