package com.churchgeniuspro.etl;

import com.churchgeniuspro.util.CsvUtil;
import com.churchgeniuspro.util.EtlTargetSchema;
import com.churchgeniuspro.util.MappingMatcher;
import com.churchgeniuspro.util.MappingMatcher.SourceCol;
import com.churchgeniuspro.util.MappingMatcher.Suggestion;
import com.churchgeniuspro.util.SourcePreset;
import com.churchgeniuspro.util.Transforms;
import com.churchgeniuspro.util.Transforms.Context;
import com.churchgeniuspro.util.Transforms.TransformException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Migration Phase 1 — pure utilities: transforms, CSV sniffing, presets, schema flags, matcher. */
@DisplayName("ETL Phase 1 — transforms, presets, schema")
class EtlTransformsAndPresetsTest {

    @Nested
    @DisplayName("Transforms.parse_date")
    class Dates {
        @Test void legacyBehaviourUnchangedForAuto() {
            assertThat(Transforms.parseDate("2024-03-05")).isEqualTo(LocalDate.of(2024, 3, 5));
            assertThat(Transforms.parseDate("3/5/2024")).isEqualTo(LocalDate.of(2024, 3, 5));     // month first
            assertThat(Transforms.parseDate("3-5-2024")).isEqualTo(LocalDate.of(2024, 3, 5));
            assertThat(Transforms.parseDate("5.3.2024")).isEqualTo(LocalDate.of(2024, 3, 5));     // dotted = day first (legacy d.M.yyyy)
            assertThat(Transforms.parseDate("3/5/24")).isEqualTo(LocalDate.of(2024, 3, 5));
        }
        @Test void autoAcceptsUnambiguousDayFirst() {
            assertThat(Transforms.parseDate("25/12/2024")).isEqualTo(LocalDate.of(2024, 12, 25));
        }
        @Test void explicitOrderResolvesAmbiguity() {
            assertThat(Transforms.parseDate("3/5/2024", Context.of("DMY"))).isEqualTo(LocalDate.of(2024, 5, 3));
            assertThat(Transforms.parseDate("3/5/2024", Context.of("MDY"))).isEqualTo(LocalDate.of(2024, 3, 5));
            assertThatThrownBy(() -> Transforms.parseDate("13/5/2024", Context.of("MDY")))
                .isInstanceOf(TransformException.class).hasMessageContaining("M/D/Y");
        }
        @Test void monthNamesTimestampsAndExcelSerials() {
            assertThat(Transforms.parseDate("Jan 5, 2024")).isEqualTo(LocalDate.of(2024, 1, 5));
            assertThat(Transforms.parseDate("5 January 2024")).isEqualTo(LocalDate.of(2024, 1, 5));
            assertThat(Transforms.parseDate("05-Mar-2024")).isEqualTo(LocalDate.of(2024, 3, 5));
            assertThat(Transforms.parseDate("2024-03-05 10:30:00")).isEqualTo(LocalDate.of(2024, 3, 5));
            assertThat(Transforms.parseDate("2024-03-05T10:30:00Z")).isEqualTo(LocalDate.of(2024, 3, 5));
            assertThat(Transforms.parseDate("03/05/2024 10:30am", Context.of("MDY"))).isEqualTo(LocalDate.of(2024, 3, 5));
            assertThat(Transforms.parseDate("45356")).isEqualTo(LocalDate.of(2024, 3, 5));          // Excel serial
            assertThatThrownBy(() -> Transforms.parseDate("yesterday")).isInstanceOf(TransformException.class);
        }
    }

    @Nested
    @DisplayName("Transforms.parse_amount and text helpers")
    class Amounts {
        @Test void currenciesAndSigns() {
            assertThat(Transforms.parseAmount("$1,250.00")).isEqualByComparingTo("1250.00");
            assertThat(Transforms.parseAmount("€ 1.234,56")).isEqualByComparingTo("1234.56");
            assertThat(Transforms.parseAmount("£40")).isEqualByComparingTo("40");
            assertThat(Transforms.parseAmount("USD 12.50")).isEqualByComparingTo("12.50");
            assertThat(Transforms.parseAmount("(123.45)")).isEqualByComparingTo("-123.45");
            assertThat(Transforms.parseAmount("123.45-")).isEqualByComparingTo("-123.45");
            assertThat(Transforms.parseAmount("-7")).isEqualByComparingTo("-7");
            assertThat(Transforms.parseAmount(".5")).isEqualByComparingTo("0.5");
            assertThatThrownBy(() -> Transforms.parseAmount("twelve")).isInstanceOf(TransformException.class);
        }
        @Test void listsAndNames() {
            assertThat(Transforms.apply("first_of_list", "a@x.org, b@x.org")).isEqualTo("a@x.org");
            assertThat(Transforms.apply("first_of_list", "; b@x.org")).isEqualTo("b@x.org");
            assertThat(Transforms.apply("normalize_phone", "913-555-0102; 913-555-0199")).isEqualTo("9135550102");
            assertThat(Transforms.splitName("Example, Grace")).containsExactly("Grace", "Example");
            assertThat(Transforms.splitName("Grace Ann Example")).containsExactly("Grace Ann", "Example");
            assertThat(Transforms.splitName("Cher")).containsExactly(null, "Cher");
            assertThat(Transforms.apply("split_name_first", "Example, Grace")).isEqualTo("Grace");
            assertThat(Transforms.apply("split_name_last", "Grace Example")).isEqualTo("Example");
            assertThat(Transforms.apply("parse_bool", "x")).isEqualTo(Boolean.TRUE);
            assertThat(Transforms.apply("trim", "   ")).isNull();
            assertThatThrownBy(() -> Transforms.apply("eval", "x")).isInstanceOf(TransformException.class);
            assertThat(EtlTargetSchema.transforms()).contains("first_of_list", "split_name_first", "split_name_last");
        }
    }

    @Nested
    @DisplayName("CsvUtil")
    class Csv {
        @Test void sniffsSemicolonTabAndPipe() {
            assertThat(CsvUtil.sniffDelimiter("Name;E-Mail;Telefon\n\"Example, Grace\";g@x.org;1")).isEqualTo(';');
            assertThat(CsvUtil.sniffDelimiter("a\tb\tc\n1\t2\t3")).isEqualTo('\t');
            assertThat(CsvUtil.sniffDelimiter("a|b|c")).isEqualTo('|');
            assertThat(CsvUtil.sniffDelimiter("\"Last, First\",Email\n")).isEqualTo(',');   // comma inside quotes ignored
            assertThat(CsvUtil.sniffDelimiter("single")).isEqualTo(',');
        }
        @Test void parsesWithSniffedDelimiterKeepingQuotedCommas() {
            String text = "Name;E-Mail\n\"Example, Grace\";g@x.org\n";
            List<List<String>> rows = CsvUtil.parse(text, CsvUtil.sniffDelimiter(text));
            assertThat(rows).hasSize(2);
            assertThat(rows.get(1)).containsExactly("Example, Grace", "g@x.org");
            assertThat(CsvUtil.parse("﻿a,b\n1,2").get(0)).containsExactly("a", "b");   // BOM stripped, legacy comma path
        }
    }

    @Nested
    @DisplayName("SourcePreset")
    class Presets {
        @Test void aliasesMatchIgnoringCaseAndPunctuation() {
            assertThat(SourcePreset.BREEZE.matches("family", "role", "Family Role")).isTrue();
            assertThat(SourcePreset.BREEZE.matches("family", "role", "family_role")).isTrue();
            assertThat(SourcePreset.BREEZE.matches("family", "birthDate", "Age")).isTrue();
            assertThat(SourcePreset.BREEZE.matches("family", "phone", "Home")).isFalse();     // only Mobile is the phone alias
            assertThat(SourcePreset.CHMEETINGS.matches("income", "familyLink", "Person Id")).isTrue();
            assertThat(SourcePreset.GENERIC.matches("family", "firstName", "First Name")).isFalse();
        }
        @Test void idColumnsAndParsing() {
            assertThat(SourcePreset.BREEZE.idColumnIn("family", List.of("First Name", "Breeze ID"))).isEqualTo("Breeze ID");
            assertThat(SourcePreset.CHMEETINGS.idColumnIn("income", List.of("Person Id", "Transaction Id"))).isEqualTo("Transaction Id");
            assertThat(SourcePreset.GENERIC.idColumnIn("family", List.of("id"))).isNull();
            assertThat(SourcePreset.of(null)).isEqualTo(SourcePreset.GENERIC);
            assertThat(SourcePreset.of("breeze")).isEqualTo(SourcePreset.BREEZE);
            assertThat(SourcePreset.of("nonsense")).isEqualTo(SourcePreset.GENERIC);
        }
        @Test void noPresetIsVerifiedUntilARealExportHasPassed() {
            for (SourcePreset p : SourcePreset.values()) assertThat(p.verified()).as(p.name()).isFalse();
            assertThat(SourcePreset.BREEZE.dateOrder()).isEqualTo("MDY");
            assertThat(SourcePreset.BREEZE.dictionary("role").get("head of household")).isEqualTo("Head of Household");
            assertThat(SourcePreset.CHMEETINGS.dictionary("method").get("wire transfer")).isEqualTo("Wire Transfer");
        }
    }

    @Nested
    @DisplayName("EtlTargetSchema and MappingMatcher")
    class Schema {
        @Test void requiredDictionaryAndVirtualFlags() {
            assertThat(EtlTargetSchema.required("income")).extracting(EtlTargetSchema.TargetColumn::name).containsExactly("amount", "incomeDate");
            assertThat(EtlTargetSchema.required("expense")).extracting(EtlTargetSchema.TargetColumn::name).containsExactly("amount", "expenseDate");
            assertThat(EtlTargetSchema.column("income", "method").dictionary()).isEqualTo("method");
            assertThat(EtlTargetSchema.column("income", "sourceName").dictionary()).isEqualTo("fund");
            assertThat(EtlTargetSchema.column("family", "fullName").virtual()).isTrue();
            assertThat(EtlTargetSchema.column("family", "birthDate").virtual()).isTrue();
            assertThat(EtlTargetSchema.column("income", "guestName")).isNotNull();
            assertThat(EtlTargetSchema.column("income", "purposeName")).as("Income has no purpose column — no longer offered").isNull();
            assertThat(EtlTargetSchema.column("expense", "familyLink")).as("Expense has no member link — no longer offered").isNull();
            assertThat(EtlTargetSchema.column("expense", "payee")).as("payee is kept (loaded into the note)").isNotNull();
        }
        @Test void matcherLeavesPresetPlacedTargetsAlone() {
            List<SourceCol> src = List.of(new SourceCol("Status", "STRING"), new SourceCol("Email", "EMAIL"));
            List<Suggestion> all = MappingMatcher.match("family", src);
            assertThat(all.stream().filter(s -> "memberType".equals(s.targetColumn)).findFirst().orElseThrow().sourceColumn).isEqualTo("Status");
            List<Suggestion> withPlaced = MappingMatcher.match("family", src, Set.of("memberType"));
            assertThat(withPlaced.stream().filter(s -> "memberType".equals(s.targetColumn)).findFirst().orElseThrow().sourceColumn).isNull();
            assertThat(withPlaced.stream().filter(s -> "email".equals(s.targetColumn)).findFirst().orElseThrow().sourceColumn).isEqualTo("Email");
        }
    }
}
