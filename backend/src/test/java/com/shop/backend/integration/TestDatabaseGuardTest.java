package com.shop.backend.integration;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** No Docker and no real database: the DataSource is a Mockito fake, so nothing can be truncated. */
class TestDatabaseGuardTest {

    private static final String CONTAINER_URL = "jdbc:postgresql://localhost:49153/test?loggerLevel=OFF";

    private static DataSource fakeDataSource(String url, String currentDatabase) throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(meta);
        when(meta.getURL()).thenReturn(url);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT current_database()")).thenReturn(rs);
        when(rs.getString(1)).thenReturn(currentDatabase);
        return ds;
    }

    @Test
    void 성공_컨테이너_DB를_가리키면_통과한다() throws Exception {
        DataSource ds = fakeDataSource("jdbc:postgresql://localhost:49153/test", "test");

        assertThatCode(() -> TestDatabaseGuard.requireContainerDatabase(ds, CONTAINER_URL, "test")).doesNotThrowAnyException();
    }

    @Test
    void 실패_컨테이너가_아닌_호스트의_DB면_TRUNCATE_전에_막는다() throws Exception {
        DataSource ds = fakeDataSource("jdbc:postgresql://db.example.invalid:5432/neondb?sslmode=require", "neondb");

        assertThatThrownBy(() -> TestDatabaseGuard.requireContainerDatabase(ds, CONTAINER_URL, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to TRUNCATE")
                .hasMessageContaining("db.example.invalid");
    }

    @Test
    void 실패_같은_주소라도_데이터베이스_이름이_다르면_막는다() throws Exception {
        DataSource ds = fakeDataSource("jdbc:postgresql://localhost:49153/test", "template1");

        assertThatThrownBy(() -> TestDatabaseGuard.requireContainerDatabase(ds, CONTAINER_URL, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current_database()");
    }
}
