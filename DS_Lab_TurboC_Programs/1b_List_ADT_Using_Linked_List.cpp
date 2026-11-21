/* Program 1(b) : List ADT using Linked List
   Compiler    : Turbo C++ 3.0                                     */
#include<iostream.h>
#include<conio.h>
#include<stdio.h>

struct Node
{
  int data;
  Node *next;
};

class List
{
 private:
   Node *head;
 public:
   List()
   {
     head=NULL;
   }

   void add(int val)          /* insert at front */
   {
     Node *temp=new Node;
     temp->data=val;
     temp->next=head;
     head=temp;
   }

   void printList()
   {
     Node *temp=head;
     if(temp==NULL)
     {
       cout<<"List is empty\n";
       return;
     }
     while(temp!=NULL)
     {
       cout<<temp->data<<" ";
       temp=temp->next;
     }
     cout<<endl;
   }

   void deleteAtPosition(int pos)   /* position starts from 0 */
   {
     Node *temp, *del;
     int i;
     if(head==NULL)
       return;
     if(pos==0)
     {
       temp=head;
       head=head->next;
       delete temp;
       return;
     }
     temp=head;
     i=0;
     while(temp!=NULL && i<pos-1)
     {
       temp=temp->next;
       i++;
     }
     if(temp==NULL || temp->next==NULL)
     {
       cout<<"Invalid position\n";
       return;
     }
     del=temp->next;
     temp->next=del->next;
     delete del;
   }

   ~List()
   {
     Node *temp;
     while(head!=NULL)
     {
       temp=head;
       head=head->next;
       delete temp;
     }
   }
};

void main()
{
  clrscr();
  {
    List list;
    cout<<"Elements are added into the list\n";
    list.add(10);
    list.add(20);
    list.add(30);
    cout<<"After Insertion the List is:\n";
    list.printList();
    cout<<"After deletion at front position the List is\n";
    list.deleteAtPosition(0);
    list.printList();
    getch();
  }
}
