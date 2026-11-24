package com.elecsarthi.entity;

import com.elecsarthi.enums.FieldUsage;
import com.elecsarthi.enums.FieldValueType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "election_type_field")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ElectionTypeField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "election_type_id", nullable = false)
    private Short electionTypeId;

    @Column(name = "source_column", nullable = false)
    private String sourceColumn;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false)
    private FieldValueType valueType;

    @Column(name = "label_en", nullable = false)
    private String labelEn;

    @Column(name = "label_mt")
    private String labelMt;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage", nullable = false)
    private FieldUsage usage;

    @Column(name = "filter_order")
    private Short filterOrder;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "on_slip", nullable = false)
    @Builder.Default
    private boolean onSlip = false;
}
