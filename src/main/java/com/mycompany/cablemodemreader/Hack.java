/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.mycompany.cablemodemreader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author bunchr
 */
public class Hack {
    public static void main(String[] args) {
        Date d = new Date();
        System.out.println(d);
        save(d, setup());
    }
    
    public static void save(Date d, Connection conn) {
        try {
            String sql = "insert into test(c1) values (?)";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            // TODO use setTimestamp, verify I get date AND time
            pstmt.setTimestamp(1, new Timestamp(d.getTime()));
            pstmt.executeQuery();
        } catch (SQLException ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public static Connection setup() {
        Connection connection = null;
        try {
            Class.forName("oracle.jdbc.OracleDriver");

            connection = DriverManager.getConnection(
                    "jdbc:oracle:thin:@localhost:1521:xe", "monitor", "version3");
//            connection.close();
        } catch (ClassNotFoundException ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        } catch (SQLException ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        }
        return connection;
    }    
}
