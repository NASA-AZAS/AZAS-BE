package azas.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "persistence_fixture")
public class PersistenceFixture {

    @Id
    private Long id;
    @Column(length = 100, nullable = false)
    private String label;

    protected PersistenceFixture() {
    }

    public PersistenceFixture(Long id, String label) {
        this.id = id;
        this.label = label;
    }

    public String label() {
        return label;
    }
}
